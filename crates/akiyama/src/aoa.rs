//! Talks to a phone as an Android Open Accessory.
//!
//! The computer asks the phone over USB to switch into accessory mode, and the phone comes back as
//! a new USB device with a pair of bulk endpoints that an app can open as a file. That's a raw
//! stream in each direction, with no USB debugging, `adb` or root on the phone.

use akiyama_core::proto::Welcome;
use akiyama_core::session::client_handshake;
use anyhow::{Context, Result, anyhow, bail};
use nusb::descriptors::TransferType;
use nusb::io::{EndpointRead, EndpointWrite};
use nusb::transfer::{Bulk, ControlIn, ControlOut, ControlType, Direction, In, Out, Recipient};
use nusb::{Device, DeviceId, DeviceInfo};
use std::collections::HashSet;
use std::io;
use std::pin::Pin;
use std::task::{Context as TaskContext, Poll, ready};
use std::time::Duration;
use tokio::io::AsyncWrite;
use tokio::time::{sleep, timeout};
use tracing::{debug, warn};

const GOOGLE_VENDOR: u16 = 0x18d1;
const ACCESSORY_PRODUCTS: std::ops::RangeInclusive<u16> = 0x2d00..=0x2d05;
const APPLE_VENDOR: u16 = 0x05ac;
const HUB_CLASS: u8 = 9;
const VENDOR_CLASS: u8 = 0xff;
const STILL_IMAGE_CLASS: u8 = 6;

const REQUEST_GET_PROTOCOL: u8 = 51;
const REQUEST_SEND_STRING: u8 = 52;
const REQUEST_START: u8 = 53;
const CONTROL_TIMEOUT: Duration = Duration::from_secs(2);
const SCAN_INTERVAL: Duration = Duration::from_millis(500);
/// How long a phone already in accessory mode gets to answer before it's asked to switch roles.
const ALREADY_SHARING_WAIT: Duration = Duration::from_secs(2);

/// The app's accessory filter matches on these two, so they have to agree with
/// `res/xml/accessory_filter.xml`.
const MANUFACTURER: &str = "Wonderhoy Labs";
const MODEL: &str = "Akiyama";
/// Where the phone sends people who don't have the app.
const URI: &str = "https://github.com/wonderhoy-labs/akiyama";

/// What this computer wants from the phone. The app reads this from the accessory's serial string,
/// so these have to agree with `TunnelService` and `ShareService`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    /// This computer shares its network with the phone.
    Server,
    /// The phone shares its network with this computer.
    Client,
}

impl Role {
    fn as_str(self) -> &'static str {
        match self {
            Role::Server => "akiyama-server",
            Role::Client => "akiyama-client",
        }
    }
}

/// Each direction keeps several transfers going at once, so the bus stays busy between them.
const TRANSFER_BYTES: usize = 64 * 1024;
const READ_TRANSFERS: usize = 8;
/// More of these only adds delay. In testing, speed stayed the same at two, and each one in flight
/// is more data queued in front of anything new.
const WRITE_TRANSFERS: usize = 4;

/// True for a phone that's already in accessory mode.
pub fn is_accessory(info: &DeviceInfo) -> bool {
    info.vendor_id() == GOOGLE_VENDOR && ACCESSORY_PRODUCTS.contains(&info.product_id())
}

/// True for a device that might be an Android phone. Asking is harmless, since anything else just
/// refuses the request, but there's no reason to bother keyboards and hubs.
pub fn might_be_a_phone(info: &DeviceInfo) -> bool {
    if info.vendor_id() == APPLE_VENDOR || info.class() == HUB_CLASS {
        return false;
    }
    let mut interfaces = info.interfaces().peekable();
    interfaces.peek().is_none()
        || interfaces.any(|interface| matches!(interface.class(), VENDOR_CLASS | STILL_IMAGE_CLASS))
}

pub fn describe(info: &DeviceInfo) -> String {
    let manufacturer = info.manufacturer_string().unwrap_or("Unknown");
    let product = info.product_string().unwrap_or("device");
    format!("{manufacturer} {product}")
}

/// Asks a phone to switch into accessory mode. Returns false if it isn't something that can. When
/// this works the device disappears and comes back as an accessory.
pub async fn switch_to_accessory(info: &DeviceInfo, role: Role) -> Result<bool> {
    let device = match info.open().await {
        Ok(device) => device,
        Err(error) => return Err(anyhow!(error).context("couldn't open it. On Linux, this needs a udev rule or root")),
    };

    let request = ControlIn {
        control_type: ControlType::Vendor,
        recipient: Recipient::Device,
        request: REQUEST_GET_PROTOCOL,
        value: 0,
        index: 0,
        length: 2,
    };
    let Ok(version) = device.control_in(request, CONTROL_TIMEOUT).await else {
        return Ok(false);
    };
    if version.len() < 2 || u16::from_le_bytes([version[0], version[1]]) == 0 {
        return Ok(false);
    }

    let host = gethostname::gethostname().to_string_lossy().into_owned();
    let version = env!("CARGO_PKG_VERSION");
    let strings = [MANUFACTURER, MODEL, host.as_str(), version, URI, role.as_str()];
    for (index, string) in strings.iter().enumerate() {
        let mut data = string.as_bytes().to_vec();
        data.push(0);
        let request = ControlOut {
            control_type: ControlType::Vendor,
            recipient: Recipient::Device,
            request: REQUEST_SEND_STRING,
            value: 0,
            index: index as u16,
            data: &data,
        };
        device.control_out(request, CONTROL_TIMEOUT).await.context("couldn't describe this accessory to the phone")?;
    }

    let start = ControlOut {
        control_type: ControlType::Vendor,
        recipient: Recipient::Device,
        request: REQUEST_START,
        value: 0,
        index: 0,
        data: &[],
    };
    device.control_out(start, CONTROL_TIMEOUT).await.context("the phone wouldn't switch to accessory mode")?;
    Ok(true)
}

/// Finds a phone that's sharing its network and says hello, waiting until one answers. With
/// `may_reset`, a phone already in accessory mode that doesn't answer gets asked to switch roles.
pub async fn connect_to_phone(serial: Option<&str>, may_reset: bool) -> Result<(Accessory, String, Welcome)> {
    let mut asked: HashSet<DeviceId> = HashSet::new();
    let mut first_look = may_reset;
    loop {
        let devices: Vec<_> = nusb::list_devices().await.context("couldn't list USB devices")?.collect();
        for info in &devices {
            if serial.is_some_and(|wanted| info.serial_number() != Some(wanted)) {
                continue;
            }
            if is_accessory(info) {
                let mut accessory = match open_accessory(info).await {
                    Ok(accessory) => accessory,
                    Err(error) if vanished(&error) => {
                        debug!("{error:#}");
                        continue;
                    }
                    Err(error) => return Err(error),
                };
                // A phone that's already in accessory mode might be sharing, which an earlier run
                // left it doing. If it's not, whatever switched it asked for something else.
                // Replugging would fix that, and so does a reset.
                if first_look && asked.insert(info.id()) {
                    let handshake = client_handshake(&mut accessory.reader, &mut accessory.writer);
                    if let Ok(welcome) = timeout(ALREADY_SHARING_WAIT, handshake).await {
                        return Ok((accessory, describe(info), welcome.context("the handshake failed")?));
                    }
                    drop(accessory);
                    ask_again(info, Role::Client).await;
                    continue;
                }
                let welcome = client_handshake(&mut accessory.reader, &mut accessory.writer)
                    .await
                    .context("the handshake failed")?;
                return Ok((accessory, describe(info), welcome));
            }
            if might_be_a_phone(info) && asked.insert(info.id()) {
                match switch_to_accessory(info, Role::Client).await {
                    Ok(true) => debug!(device = %describe(info), "asked it to switch to accessory mode"),
                    Ok(false) => debug!(device = %describe(info), "it doesn't speak the accessory protocol"),
                    Err(error) => warn!(device = %describe(info), "{error:#}"),
                }
            }
        }
        first_look = false;
        sleep(SCAN_INTERVAL).await;
    }
}

/// True when a phone went away mid-operation, which is normal while it's switching modes.
pub fn vanished(error: &anyhow::Error) -> bool {
    error
        .chain()
        .filter_map(|cause| cause.downcast_ref::<nusb::Error>())
        .any(|error| matches!(error.kind(), nusb::ErrorKind::Disconnected | nusb::ErrorKind::NotFound))
}

/// Changes what a phone already in accessory mode was asked for, as if it had just been plugged in.
///
/// A reset alone doesn't do it. Android waits a second before believing in a disconnect, so after a
/// quick reset it can come back still in accessory mode, with the old role. So it gets the new
/// strings first, and the reset is what makes it notice them.
///
/// Linux reports the reset as a failure, because the phone really does drop off the bus straight
/// away. Either way, what to do next is look for it again.
pub async fn ask_again(info: &DeviceInfo, role: Role) {
    if let Err(error) = switch_to_accessory(info, role).await {
        debug!("telling the phone what's wanted: {error:#}");
    }
    if let Err(error) = reset(info).await {
        debug!("resetting the phone: {error:#}");
    }
}

async fn reset(info: &DeviceInfo) -> Result<()> {
    let device = info.open().await.context("couldn't open it")?;
    device.reset().await.context("couldn't reset it")?;
    Ok(())
}

/// Writes to a phone. Ending each burst properly is the difference between working and hanging: the
/// phone's read only finishes on a short packet, so a burst that's an exact number of packets long
/// needs an empty one after it.
pub struct UsbWriter(EndpointWrite<Bulk>);

impl AsyncWrite for UsbWriter {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut TaskContext<'_>, data: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.0).poll_write(cx, data)
    }

    /// Sends what's buffered without waiting for it to land, so that transfers overlap. If too many
    /// are in flight, the next write waits instead.
    fn poll_flush(mut self: Pin<&mut Self>, _cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        self.0.submit_end();
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        self.0.submit_end();
        ready!(Pin::new(&mut self.0).poll_flush(cx))?;
        Poll::Ready(Ok(()))
    }
}

/// An open accessory. Dropping it releases the USB interface.
pub struct Accessory {
    pub reader: EndpointRead<Bulk>,
    pub writer: UsbWriter,
}

/// Claims the accessory's bulk endpoints.
pub async fn open_accessory(info: &DeviceInfo) -> Result<Accessory> {
    let device = info.open().await.context("couldn't open the phone. On Linux, this needs a udev rule or root")?;
    let (number, address_in, address_out) = find_endpoints(&device)?;
    let interface = device.claim_interface(number).await.context("couldn't claim the accessory's USB interface")?;
    let endpoint_in =
        interface.endpoint::<Bulk, In>(address_in).context("couldn't open the accessory's incoming endpoint")?;
    let endpoint_out =
        interface.endpoint::<Bulk, Out>(address_out).context("couldn't open the accessory's outgoing endpoint")?;
    Ok(Accessory {
        reader: endpoint_in.reader(TRANSFER_BYTES).with_num_transfers(READ_TRANSFERS),
        writer: UsbWriter(endpoint_out.writer(TRANSFER_BYTES).with_num_transfers(WRITE_TRANSFERS)),
    })
}

/// The accessory is the vendor-specific interface that isn't `adb`, which is subclass 0x42.
fn find_endpoints(device: &Device) -> Result<(u8, u8, u8)> {
    const ADB_SUBCLASS: u8 = 0x42;
    let configuration = device.active_configuration().context("couldn't read the accessory's configuration")?;
    for interface in configuration.interfaces() {
        for setting in interface.alt_settings() {
            if setting.class() != VENDOR_CLASS || setting.subclass() == ADB_SUBCLASS {
                continue;
            }
            let bulk = |direction| {
                setting
                    .endpoints()
                    .find(|endpoint| {
                        endpoint.transfer_type() == TransferType::Bulk && endpoint.direction() == direction
                    })
                    .map(|endpoint| endpoint.address())
            };
            if let (Some(input), Some(output)) = (bulk(Direction::In), bulk(Direction::Out)) {
                return Ok((setting.interface_number(), input, output));
            }
        }
    }
    bail!("this accessory has no usable USB interface")
}

/// `akiyama devices`
pub async fn print_devices() -> Result<()> {
    let devices = nusb::list_devices().await.context("couldn't list USB devices")?;
    let mut found = 0;
    for info in devices {
        let state = if is_accessory(&info) {
            "in accessory mode"
        } else if might_be_a_phone(&info) {
            "might be a phone"
        } else {
            continue;
        };
        println!(
            "{:04x}:{:04x}  {}  {}  {state}",
            info.vendor_id(),
            info.product_id(),
            describe(&info),
            info.serial_number().unwrap_or("(no serial)"),
        );
        found += 1;
    }
    if found == 0 {
        println!("Nothing that looks like a phone. Is it plugged in?");
    }
    Ok(())
}

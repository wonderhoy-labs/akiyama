//! `akiyama client`: joins a phone's network through a TUN device, and keeps the link up.

use crate::tunnel::Tunnel;
use crate::{ClientArgs, aoa, shutdown_signal};
use akiyama_core::session::{ClientDevice, SessionEnd, Stats, run_client};
use anyhow::Result;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;
use tokio::sync::watch;
use tokio::time::sleep;
use tracing::{info, warn};

const RETRY_DELAY: Duration = Duration::from_secs(1);

pub async fn run(args: ClientArgs) -> Result<()> {
	let (stop_tx, stop_rx) = watch::channel(false);
	tokio::spawn(async move {
		shutdown_signal().await;
		let _ = stop_tx.send(true);
	});
	let mut stop = stop_rx.clone();
	let stopped = async move {
		let _ = stop.wait_for(|stopped| *stopped).await;
	};
	tokio::pin!(stopped);

	info!("waiting for an Android phone. Plug one in with a USB cable, then tap Share in the Akiyama app");
	let mut state: Option<(Tunnel, ClientDevice)> = None;
	// Only the first time. Someone who taps Stop sharing shouldn't get asked again straight away.
	let mut may_reset = true;
	loop {
		let connect = aoa::connect_to_phone(args.serial.as_deref(), may_reset);
		let (aoa::Accessory { mut reader, mut writer }, phone, welcome) = tokio::select! {
			() = &mut stopped => return Ok(()),
			result = connect => match result {
				Ok(connected) => connected,
				Err(error) if args.once => return Err(error),
				Err(error) => {
					warn!("{error:#}. Trying again");
					sleep(RETRY_DELAY).await;
					continue;
				}
			},
		};

		may_reset = false;
		if state.is_none() {
			let (tunnel, device) = Tunnel::open(args.dev.as_deref(), &welcome, !args.no_routes, !args.no_dns)?;
			state = Some((tunnel, ClientDevice::new(device, &welcome)));
		}
		let (tunnel, device) = state.as_mut().expect("the device was just opened");
		info!(%phone, device = tunnel.name(), address = %welcome.client_v4, "connected");

		let stats = Arc::new(Stats::default());
		let result = run_client(&mut reader, &mut writer, device, &welcome, stats.clone(), &mut stopped).await;
		let sent = stats.bytes_sent.load(Ordering::Relaxed);
		let received = stats.bytes_received.load(Ordering::Relaxed);
		match result {
			Ok(SessionEnd::Stopped) => {
				info!(sent, received, "disconnected");
				return Ok(());
			}
			Ok(_) => info!(sent, received, "the phone stopped sharing"),
			Err(error) => warn!(sent, received, %error, "the link failed"),
		}
		if args.once {
			return Ok(());
		}
		sleep(RETRY_DELAY).await;
	}
}

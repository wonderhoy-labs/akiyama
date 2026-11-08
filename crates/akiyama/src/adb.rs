//! Reaches a phone through `adb`, for when it can't be a USB accessory. That's mainly Windows, where
//! a USB accessory would need a driver installed by hand. It needs USB debugging on the phone.

use anyhow::{Context, Result, bail};
use std::path::PathBuf;
use std::process::Output;
use tokio::process::Command;

/// The socket name on the phone. The app connects to this name, and `adb reverse` carries it here.
const SOCKET_NAME: &str = "localabstract:akiyama";
const APP_ACTIVITY: &str = "com.wonderhoylabs.akiyama/.ui.MainActivity";

/// Finds `adb`, on the path or where the Android SDK keeps it.
pub fn find_adb() -> Result<PathBuf> {
	let name = if cfg!(windows) { "adb.exe" } else { "adb" };
	let on_path =
		std::env::var_os("PATH").into_iter().flat_map(|path| std::env::split_paths(&path).collect::<Vec<_>>());
	let in_sdk = ["ANDROID_HOME", "ANDROID_SDK_ROOT"]
		.iter()
		.filter_map(std::env::var_os)
		.map(|sdk| PathBuf::from(sdk).join("platform-tools"));
	on_path
		.chain(in_sdk)
		.map(|folder| folder.join(name))
		.find(|candidate| candidate.is_file())
		.context("couldn't find adb. Install Android's platform-tools and put them on your path")
}

async fn run(adb: &PathBuf, arguments: &[&str]) -> Result<Output> {
	Command::new(adb).args(arguments).output().await.with_context(|| format!("couldn't run {}", adb.display()))
}

/// The serial numbers of the phones `adb` can use.
pub async fn devices(adb: &PathBuf) -> Result<Vec<String>> {
	let output = run(adb, &["devices"]).await?;
	if !output.status.success() {
		bail!("adb devices failed: {}", String::from_utf8_lossy(&output.stderr).trim());
	}
	let listing = String::from_utf8_lossy(&output.stdout);
	Ok(listing
		.lines()
		.skip(1)
		.filter_map(|line| line.split_once('\t'))
		.filter(|(_, state)| state.trim() == "device")
		.map(|(serial, _)| serial.to_owned())
		.collect())
}

/// `akiyama devices`, on Windows.
#[cfg(windows)]
pub async fn print_devices() -> Result<()> {
	let phones = devices(&find_adb()?).await?;
	if phones.is_empty() {
		println!("No phones. Is one plugged in, with USB debugging on?");
	}
	for phone in phones {
		println!("{phone}");
	}
	Ok(())
}

/// Removes what [`connect_phone`] set up. There's nothing to do if the phone has gone.
pub async fn disconnect_phone(adb: &PathBuf, serial: &str) {
	let _ = run(adb, &["-s", serial, "reverse", "--remove", SOCKET_NAME]).await;
}

/// Makes the app's socket on the phone lead to `port` here, and opens the app.
pub async fn connect_phone(adb: &PathBuf, serial: &str, port: u16) -> Result<()> {
	let target = format!("tcp:{port}");
	let output = run(adb, &["-s", serial, "reverse", SOCKET_NAME, &target]).await?;
	if !output.status.success() {
		bail!("adb reverse failed: {}", String::from_utf8_lossy(&output.stderr).trim());
	}
	run(adb, &["-s", serial, "shell", "am", "start", "-n", APP_ACTIVITY]).await?;
	Ok(())
}

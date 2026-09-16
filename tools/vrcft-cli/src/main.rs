//! Diagnostic CLI for the VRCFT-ALVR module wire protocol.
//!
//! `vrcft-cli listen` impersonates the VRCFT module: binds the module port and
//! prints decoded packets, verifying the headset -> PC path without VRCFaceTracking.
//!
//! `vrcft-cli send` impersonates the headset: sends synthetic animated packets,
//! verifying the PC -> VRCFT -> VRChat path without the headset.

use ftbridge_protocol::{FaceData, FaceExpressions, VRCFT_PORT};
use std::{
    net::UdpSocket,
    process::ExitCode,
    time::{Duration, Instant},
};

const PRINT_INTERVAL: Duration = Duration::from_secs(1);
const HTC_LEFT_BLINK: usize = 0;
const HTC_RIGHT_BLINK: usize = 2;
const HTC_JAW_OPEN: usize = 3;
const HTC_MOUTH_SMILE_RIGHT: usize = 12;
const HTC_MOUTH_SMILE_LEFT: usize = 13;

fn print_usage() {
    eprintln!(
        "usage:
  vrcft-cli listen [port]
      Bind 0.0.0.0:<port> (default {VRCFT_PORT}) and print decoded packets.
      VRCFaceTracking must be closed, it occupies the same port.

  vrcft-cli send [target] [preset] [rate_hz]
      Send synthetic packets to <target> (default 127.0.0.1:{VRCFT_PORT}).
      Presets: blink, jaw, smile, all (default), neutral."
    );
}

fn listen(port: u16) -> Result<(), String> {
    let socket = UdpSocket::bind(("0.0.0.0", port))
        .map_err(|e| format!("cannot bind port {port}: {e} (is VRCFaceTracking running?)"))?;

    println!("listening on 0.0.0.0:{port} ...");

    let mut packet = [0u8; 2048];
    let mut packet_count = 0u64;
    let mut last_print = Instant::now();

    loop {
        let (size, from) = socket
            .recv_from(&mut packet)
            .map_err(|e| format!("receive failed: {e}"))?;
        packet_count += 1;

        if last_print.elapsed() < PRINT_INTERVAL {
            continue;
        }
        last_print = Instant::now();

        match ftbridge_protocol::decode_vrcft_packet(&packet[..size]) {
            Ok(segments) => {
                let summary = segments
                    .iter()
                    .map(|segment| {
                        let preview = segment
                            .values
                            .iter()
                            .take(4)
                            .map(|v| format!("{v:+.3}"))
                            .collect::<Vec<_>>()
                            .join(" ");

                        format!("{}[{}]({preview} ...)", segment.name, segment.values.len())
                    })
                    .collect::<Vec<_>>()
                    .join(" | ");

                println!("#{packet_count} from {from}: {summary}");
            }
            Err(e) => println!("#{packet_count} from {from}: BAD PACKET: {e}"),
        }
    }
}

fn send(target: &str, preset: &str, rate_hz: f32) -> Result<(), String> {
    let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| format!("cannot bind socket: {e}"))?;
    socket
        .connect(target)
        .map_err(|e| format!("invalid target {target}: {e}"))?;

    println!("sending preset '{preset}' to {target} at {rate_hz} Hz, ctrl-c to stop ...");

    let interval = Duration::from_secs_f32(1.0 / rate_hz);
    let start = Instant::now();
    let mut buffer = vec![];
    let mut packet_count = 0u64;
    let mut last_print = Instant::now();

    loop {
        // 0..1 triangle wave with a 2 second period, so motion is clearly visible
        let phase = (start.elapsed().as_secs_f32() / 2.0).fract();
        let wave = 1.0 - (phase * 2.0 - 1.0).abs();

        let mut eye = vec![0.0; ftbridge_protocol::HTC_EYE_EXPRESSION_COUNT];
        let mut lip = vec![0.0; ftbridge_protocol::HTC_LIP_EXPRESSION_COUNT];
        match preset {
            "blink" => {
                eye[HTC_LEFT_BLINK] = wave;
                eye[HTC_RIGHT_BLINK] = wave;
            }
            "jaw" => lip[HTC_JAW_OPEN] = wave,
            "smile" => {
                lip[HTC_MOUTH_SMILE_LEFT] = wave;
                lip[HTC_MOUTH_SMILE_RIGHT] = wave;
            }
            "all" => {
                eye[HTC_LEFT_BLINK] = wave;
                eye[HTC_RIGHT_BLINK] = wave;
                lip[HTC_JAW_OPEN] = wave;
                lip[HTC_MOUTH_SMILE_LEFT] = 1.0 - wave;
                lip[HTC_MOUTH_SMILE_RIGHT] = 1.0 - wave;
            }
            "neutral" => (),
            other => return Err(format!("unknown preset: {other}")),
        }

        let face_data = FaceData {
            face_expressions: Some(FaceExpressions::Htc {
                eye: Some(eye),
                lip: Some(lip),
            }),
            ..Default::default()
        };

        ftbridge_protocol::encode_vrcft_packet(&face_data, &mut buffer);
        socket
            .send(&buffer)
            .map_err(|e| format!("send failed: {e}"))?;
        packet_count += 1;

        if last_print.elapsed() >= PRINT_INTERVAL {
            last_print = Instant::now();
            println!("sent {packet_count} packets, wave={wave:.2}");
        }

        std::thread::sleep(interval);
    }
}

fn main() -> ExitCode {
    let args = std::env::args().skip(1).collect::<Vec<_>>();

    let result = match args.first().map(String::as_str) {
        Some("listen") => {
            let port = args
                .get(1)
                .map(|s| s.parse::<u16>().map_err(|e| format!("invalid port: {e}")))
                .unwrap_or(Ok(VRCFT_PORT));

            port.and_then(listen)
        }
        Some("send") => {
            let default_target = format!("127.0.0.1:{VRCFT_PORT}");
            let target = args.get(1).map(String::as_str).unwrap_or(&default_target);
            let preset = args.get(2).map(String::as_str).unwrap_or("all");
            let rate_hz = args
                .get(3)
                .map(|s| s.parse::<f32>().map_err(|e| format!("invalid rate: {e}")))
                .unwrap_or(Ok(60.0));

            rate_hz.and_then(|rate| send(target, preset, rate))
        }
        _ => {
            print_usage();

            return ExitCode::FAILURE;
        }
    };

    if let Err(message) = result {
        eprintln!("error: {message}");

        ExitCode::FAILURE
    } else {
        ExitCode::SUCCESS
    }
}

//! Wire format of the VRCFT-ALVR module (https://github.com/alvr-org/VRCFT-ALVR).
//!
//! A datagram is a concatenation of segments, each being an 8 byte ASCII prefix
//! followed by a fixed number of little-endian f32 values. The format and the
//! segment ordering follow ALVR's `FaceTrackingSink` so the unmodified module
//! can consume our packets.

use std::fmt::Write;

pub const VRCFT_PORT: u16 = 0xA1F7;
pub const PREFIX_SIZE: usize = 8;

pub const EYES_QUAT_PREFIX: [u8; PREFIX_SIZE] = *b"EyesQuat";
pub const COMBINED_QUAT_PREFIX: [u8; PREFIX_SIZE] = *b"CombQuat";
pub const FB_FACE1_PREFIX: [u8; PREFIX_SIZE] = *b"FaceFb\0\0";
pub const FB_FACE2_PREFIX: [u8; PREFIX_SIZE] = *b"Face2Fb\0";
pub const BD_FACE_PREFIX: [u8; PREFIX_SIZE] = *b"FacePico";
pub const HTC_EYE_PREFIX: [u8; PREFIX_SIZE] = *b"EyesHtc\0";
pub const HTC_LIP_PREFIX: [u8; PREFIX_SIZE] = *b"LipHtc\0\0";
// Not part of ALVR's format: XR_HTC_eye_tracker data, only understood by the VRCFT-ViveBridge
// fork of the module. The stock module logs errors for unknown segments, so it is opt-in.
pub const HTC_EYE_TRACKER_PREFIX: [u8; PREFIX_SIZE] = *b"EyeTrHtc";

// Per eye: gaze valid, gaze quaternion (4), pupil diameter valid, pupil diameter in mm
pub const HTC_EYE_TRACKER_VALUE_COUNT: usize = 14;
pub const HTC_EYE_EXPRESSION_COUNT: usize = 14;
pub const HTC_LIP_EXPRESSION_COUNT: usize = 37;
pub const FB_FACE1_EXPRESSION_COUNT: usize = 63;
pub const FB_FACE2_EXPRESSION_COUNT: usize = 70;
pub const BD_FACE_EXPRESSION_COUNT: usize = 52;

// (prefix, value count, human readable name)
pub const SEGMENT_TABLE: &[([u8; PREFIX_SIZE], usize, &str)] = &[
    (EYES_QUAT_PREFIX, 8, "EyesQuat"),
    (COMBINED_QUAT_PREFIX, 4, "CombQuat"),
    (FB_FACE1_PREFIX, FB_FACE1_EXPRESSION_COUNT, "FaceFb"),
    (FB_FACE2_PREFIX, FB_FACE2_EXPRESSION_COUNT, "Face2Fb"),
    (BD_FACE_PREFIX, BD_FACE_EXPRESSION_COUNT, "FacePico"),
    (HTC_EYE_PREFIX, HTC_EYE_EXPRESSION_COUNT, "EyesHtc"),
    (HTC_LIP_PREFIX, HTC_LIP_EXPRESSION_COUNT, "LipHtc"),
    (HTC_EYE_TRACKER_PREFIX, HTC_EYE_TRACKER_VALUE_COUNT, "EyeTrHtc"),
];

#[derive(Clone, Debug)]
pub enum FaceExpressions {
    Fb(Vec<f32>),  // 70 values
    Bd(Vec<f32>),  // 52 values
    Htc {
        eye: Option<Vec<f32>>, // 14 values
        lip: Option<Vec<f32>>, // 37 values
    },
}

/// One eye of `XR_HTC_eye_tracker`. Both values are absent while the eye is closed.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct HtcEyeTrackerEye {
    // Gaze orientation as [x, y, z, w], head relative, -Z forward
    pub gaze: Option<[f32; 4]>,
    pub pupil_diameter_mm: Option<f32>,
}

#[derive(Clone, Debug, Default)]
pub struct FaceData {
    // Quaternions as [x, y, z, w]
    pub eyes_combined: Option<[f32; 4]>,
    pub eyes_social: [Option<[f32; 4]>; 2],
    // Left, right. Sent as the EyeTrHtc segment, see HTC_EYE_TRACKER_PREFIX
    pub htc_eye_tracker: Option<[HtcEyeTrackerEye; 2]>,

    pub face_expressions: Option<FaceExpressions>,
}

#[derive(Clone, Debug)]
pub struct Segment {
    pub name: &'static str,
    pub values: Vec<f32>,
}

fn append_segment(buffer: &mut Vec<u8>, prefix: [u8; PREFIX_SIZE], values: &[f32]) {
    buffer.extend(prefix);

    for value in values {
        buffer.extend(value.to_le_bytes());
    }
}

/// Build one datagram for the VRCFT module. Returns false if there was nothing to send.
/// Segment order matches ALVR: gaze quaternions first, then expressions; the module
/// applies segments in order, so HTC eye expressions override quaternion gaze like in ALVR.
pub fn encode_vrcft_packet(face_data: &FaceData, buffer: &mut Vec<u8>) -> bool {
    buffer.clear();

    if let [Some(left_quat), Some(right_quat)] = face_data.eyes_social {
        let mut values = left_quat.to_vec();
        values.extend_from_slice(&right_quat);
        append_segment(buffer, EYES_QUAT_PREFIX, &values);
    } else if let Some(quat) = face_data.eyes_combined {
        append_segment(buffer, COMBINED_QUAT_PREFIX, &quat);
    }

    if let Some(eyes) = &face_data.htc_eye_tracker {
        let mut values = Vec::with_capacity(HTC_EYE_TRACKER_VALUE_COUNT);
        for eye in eyes {
            values.push(if eye.gaze.is_some() { 1.0 } else { 0.0 });
            values.extend(eye.gaze.unwrap_or([0.0, 0.0, 0.0, 1.0]));
            values.push(if eye.pupil_diameter_mm.is_some() { 1.0 } else { 0.0 });
            values.push(eye.pupil_diameter_mm.unwrap_or(0.0));
        }
        append_segment(buffer, HTC_EYE_TRACKER_PREFIX, &values);
    }

    match &face_data.face_expressions {
        Some(FaceExpressions::Fb(values)) => {
            append_segment(buffer, FB_FACE2_PREFIX, values);
        }
        Some(FaceExpressions::Bd(values)) => {
            append_segment(buffer, BD_FACE_PREFIX, values);
        }
        Some(FaceExpressions::Htc { eye, lip }) => {
            if let Some(values) = eye {
                append_segment(buffer, HTC_EYE_PREFIX, values);
            }

            if let Some(values) = lip {
                append_segment(buffer, HTC_LIP_PREFIX, values);
            }
        }
        None => (),
    }

    !buffer.is_empty()
}

/// Parse a datagram the same way the VRCFT module does. Used by the diagnostic CLI.
pub fn decode_vrcft_packet(packet: &[u8]) -> Result<Vec<Segment>, String> {
    let mut segments = vec![];

    let mut cursor = 0;
    while packet.len() - cursor >= PREFIX_SIZE {
        let prefix = &packet[cursor..cursor + PREFIX_SIZE];
        cursor += PREFIX_SIZE;

        let (count, name) = SEGMENT_TABLE
            .iter()
            .find(|(known_prefix, _, _)| known_prefix == prefix)
            .map(|(_, count, name)| (*count, *name))
            .ok_or_else(|| format!("unrecognized prefix: {}", format_prefix(prefix)))?;

        let mut values = Vec::with_capacity(count);
        for _ in 0..count {
            let bytes = packet
                .get(cursor..cursor + 4)
                .ok_or_else(|| format!("truncated {name} segment"))?;

            // # Safety: get() above guarantees a 4 byte slice
            values.push(f32::from_le_bytes(bytes.try_into().unwrap()));
            cursor += 4;
        }

        segments.push(Segment { name, values });
    }

    if cursor != packet.len() {
        return Err(format!("{} trailing bytes", packet.len() - cursor));
    }

    Ok(segments)
}

fn format_prefix(prefix: &[u8]) -> String {
    let mut out = String::new();

    for byte in prefix {
        if byte.is_ascii_graphic() {
            out.push(*byte as char);
        } else {
            // # Safety: writing to a String cannot fail
            write!(out, "\\x{byte:02x}").unwrap();
        }
    }

    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_htc() {
        let face_data = FaceData {
            eyes_combined: Some([0.0, 0.1, 0.2, 1.0]),
            eyes_social: [None, None],
            htc_eye_tracker: None,
            face_expressions: Some(FaceExpressions::Htc {
                eye: Some((0..14).map(|i| i as f32 * 0.1).collect()),
                lip: Some((0..37).map(|i| i as f32 * 0.01).collect()),
            }),
        };

        let mut buffer = vec![];
        assert!(encode_vrcft_packet(&face_data, &mut buffer));
        assert_eq!(buffer.len(), 8 + 4 * 4 + 8 + 14 * 4 + 8 + 37 * 4);

        let segments = decode_vrcft_packet(&buffer).unwrap();
        assert_eq!(segments.len(), 3);
        assert_eq!(segments[0].name, "CombQuat");
        assert_eq!(segments[1].name, "EyesHtc");
        assert_eq!(segments[2].name, "LipHtc");
        assert_eq!(segments[1].values[3], 0.3);
    }

    #[test]
    fn social_eyes_take_precedence() {
        let face_data = FaceData {
            eyes_combined: Some([0.0; 4]),
            eyes_social: [Some([0.0, 0.0, 0.0, 1.0]), Some([0.0, 0.0, 0.0, 1.0])],
            htc_eye_tracker: None,
            face_expressions: None,
        };

        let mut buffer = vec![];
        assert!(encode_vrcft_packet(&face_data, &mut buffer));

        let segments = decode_vrcft_packet(&buffer).unwrap();
        assert_eq!(segments.len(), 1);
        assert_eq!(segments[0].name, "EyesQuat");
        assert_eq!(segments[0].values.len(), 8);
    }

    #[test]
    fn htc_eye_tracker_segment_layout() {
        let face_data = FaceData {
            htc_eye_tracker: Some([
                HtcEyeTrackerEye {
                    gaze: Some([0.1, 0.2, 0.3, 0.9]),
                    pupil_diameter_mm: Some(6.5),
                },
                // A closed eye: flags 0, identity quaternion, no diameter
                HtcEyeTrackerEye::default(),
            ]),
            ..Default::default()
        };

        let mut buffer = vec![];
        assert!(encode_vrcft_packet(&face_data, &mut buffer));

        let segments = decode_vrcft_packet(&buffer).unwrap();
        assert_eq!(segments.len(), 1);
        assert_eq!(segments[0].name, "EyeTrHtc");
        assert_eq!(
            segments[0].values,
            [1.0, 0.1, 0.2, 0.3, 0.9, 1.0, 6.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0]
        );
    }

    #[test]
    fn empty_face_data_encodes_nothing() {
        let mut buffer = vec![0xff];
        assert!(!encode_vrcft_packet(&FaceData::default(), &mut buffer));
        assert!(buffer.is_empty());
    }

    #[test]
    fn unknown_prefix_is_rejected() {
        assert!(decode_vrcft_packet(b"BadData\0\0\0\0\0").is_err());
    }
}

// Shared fragments used by more than one shot.

// The palette, mirrored from app/src/main/java/com/yinling/hotline/Ui.kt.
// If the app's Elder tokens change, these three lines are the only place to edit.
const vec3 BRAND     = vec3(0.102, 0.498, 0.420); // #1A7F6B
const vec3 BRAND_DEEP= vec3(0.071, 0.376, 0.310); // #12604F
const vec3 ATTENTION = vec3(0.769, 0.416, 0.078); // #C46A14
const vec3 PROBLEM   = vec3(0.753, 0.224, 0.169); // #C0392B
const vec3 GOOD      = vec3(0.118, 0.518, 0.286); // #1E8449
const vec3 INK       = vec3(0.090, 0.125, 0.165); // #17202A

// Smooth on/off ramp. Every shot uses this for entrances so nothing ever
// appears instantly — an instant appearance reads as a dropped frame.
float ramp(float t, float start, float dur) {
  return smoothstep(start, start + dur, t);
}

// A single decaying ripple, for the microphone ring and the button press.
float ripple(float r, float t, float speed, float width) {
  float wave = sin(r * 34.0 - t * speed);
  return smoothstep(1.0 - width, 1.0, wave) * exp(-r * 1.6);
}

// Anti-aliased disc in UV space, centred.
float disc(vec2 uv, float radius, float softness) {
  float d = length(uv - 0.5) * 2.0;
  return 1.0 - smoothstep(radius - softness, radius + softness, d);
}

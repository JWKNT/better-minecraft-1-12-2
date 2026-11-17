// Doubles a picture with the "dual filter" upsample (eight smoothed taps around the pixel);
// added onto the larger level below it by blending.
uniform sampler2D source;
uniform vec2 texel;            // one pixel of the source
uniform float weight;

varying vec2 uv;

void main() {
    vec3 s = texture2D(source, uv + texel * vec2(-2.0, 0.0)).rgb
           + texture2D(source, uv + texel * vec2(2.0, 0.0)).rgb
           + texture2D(source, uv + texel * vec2(0.0, -2.0)).rgb
           + texture2D(source, uv + texel * vec2(0.0, 2.0)).rgb
           + (texture2D(source, uv + texel * vec2(-1.0, -1.0)).rgb
           + texture2D(source, uv + texel * vec2(1.0, -1.0)).rgb
           + texture2D(source, uv + texel * vec2(-1.0, 1.0)).rgb
           + texture2D(source, uv + texel * vec2(1.0, 1.0)).rgb) * 2.0;
    gl_FragColor = vec4(s * (weight / 12.0), 1.0);
}

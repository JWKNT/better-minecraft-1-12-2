// Halves a picture: the centre and four diagonal taps, each a smoothed read of four pixels (the
// "dual filter" downsample). The first pass also keeps only what is bright, with a soft edge.
uniform sampler2D source;
uniform vec2 texel;            // one pixel of the source
#ifdef FIRST
uniform sampler2D depthTex;    // the opaque world's depth, to tell the open sky apart
uniform float hasDepth;
#endif

varying vec2 uv;

const float THRESHOLD = 0.80;
const float KNEE = 0.15;
// The open sky is large and bright but is no light: only the sun itself glows there.
const float SKY_THRESHOLD = 0.97;

void main() {
    vec3 sum = texture2D(source, uv).rgb * 4.0
             + texture2D(source, uv + texel * vec2(-1.0, -1.0)).rgb
             + texture2D(source, uv + texel * vec2(1.0, -1.0)).rgb
             + texture2D(source, uv + texel * vec2(-1.0, 1.0)).rgb
             + texture2D(source, uv + texel * vec2(1.0, 1.0)).rgb;
    vec3 c = sum / 8.0;
#ifdef FIRST
    float threshold = hasDepth > 0.5 && texture2D(depthTex, uv).r >= 1.0 ? SKY_THRESHOLD : THRESHOLD;
    float l = luma(c);
    float soft = clamp(l - threshold + KNEE, 0.0, 2.0 * KNEE);
    soft = soft * soft / (4.0 * KNEE + 0.0001);
    c *= max(soft, l - threshold) / max(l, 0.0001);
#endif
    gl_FragColor = vec4(c, 1.0);
}

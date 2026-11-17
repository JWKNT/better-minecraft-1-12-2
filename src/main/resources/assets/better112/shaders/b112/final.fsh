// Last pass over the finished world (hand included): bloom, then the colour grade.
uniform sampler2D scene;
uniform sampler2D bloomTex;
uniform float bloomStrength;
uniform float saturation;
uniform float contrast;
uniform float vignette;
uniform float sunElev;         // sine of the sun's height, > 0 by day
uniform float tintAmount;

varying vec2 uv;

vec3 curve(vec3 x) {
    // A gentle S around mid grey, kept inside 0..1.
    vec3 s = x * x * (3.0 - 2.0 * x);
    return mix(x, s, contrast);
}

void main() {
    vec3 c = texture2D(scene, uv).rgb;
#ifdef BLOOM
    c += texture2D(bloomTex, uv).rgb * bloomStrength;
#endif
    float high = smoothstep(0.03, 0.5, abs(sunElev));
    vec3 grade = sunElev > 0.0 ? mix(vec3(1.05, 1.0, 0.93), vec3(1.0, 1.0, 0.99), high) : vec3(0.95, 0.98, 1.05);
    c *= mix(vec3(1.0), grade, tintAmount);
    float l = luma(c);
    c = mix(vec3(l), c, saturation);
    // The S curve only where there is light to shape: dark places (night, caves) keep their detail.
    c = clamp(c, 0.0, 1.0);
    c = mix(c, curve(c), smoothstep(0.04, 0.35, l));
    vec2 d = uv - 0.5;
    c *= 1.0 - dot(d, d) * vignette;
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}

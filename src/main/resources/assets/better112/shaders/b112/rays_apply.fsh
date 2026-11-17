// Adds the light shafts onto the picture (blended additively), in the sun's colour.
uniform sampler2D raysTex;
uniform vec2 sunPos;
uniform float strength;        // how much the camera faces the sun, times the setting, times the time of day
uniform float sunElev;
uniform float tintAmount;
uniform float aspect;          // width / height

varying vec2 uv;

void main() {
    float light = texture2D(raysTex, uv).r;
    vec2 d = (uv - sunPos) * vec2(aspect, 1.0);
    float near = 1.0 - smoothstep(0.0, 1.1, length(d));
    float high = smoothstep(0.03, 0.55, abs(sunElev));
    vec3 color = mix(vec3(1.0, 0.78, 0.52), vec3(1.0, 0.96, 0.88), high);
    color = mix(vec3(1.0, 0.95, 0.85), color, tintAmount);
    gl_FragColor = vec4(color * light * strength * near, 1.0);
}

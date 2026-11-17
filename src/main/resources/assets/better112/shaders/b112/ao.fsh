// Ambient occlusion from the depth of the opaque world, at half size: corners, the ground
// under trees and the foot of walls get a little darker, beyond the game's own per-block shading.
uniform sampler2D depthTex;
uniform vec2 texel;            // one pixel of the depth picture
uniform mat4 projInverse;
uniform float pixelsPerUnit;   // screen pixels one block covers at distance 1

varying vec2 uv;

const float RADIUS = 2.0;      // blocks
const float STRENGTH = 0.75;
const int SAMPLES = 10;

vec3 viewAt(vec2 p) {
    float d = texture2D(depthTex, p).r;
    vec4 v = projInverse * vec4(vec3(p, d) * 2.0 - 1.0, 1.0);
    return v.xyz / v.w;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    float d = texture2D(depthTex, uv).r;
    if (d >= 1.0) {
        gl_FragColor = vec4(1.0);
        return;
    }
    vec3 p = viewAt(uv);
    vec3 px = viewAt(uv + vec2(texel.x, 0.0)) - p;
    vec3 py = viewAt(uv + vec2(0.0, texel.y)) - p;
    vec3 nx = viewAt(uv - vec2(texel.x, 0.0)) - p;
    vec3 ny = viewAt(uv - vec2(0.0, texel.y)) - p;
    // The smaller step on each side, so an edge does not bend the normal.
    vec3 dx = length(px) < length(nx) ? px : -nx;
    vec3 dy = length(py) < length(ny) ? py : -ny;
    vec3 n = normalize(cross(dx, dy));
    float reach = clamp(RADIUS * pixelsPerUnit / -p.z, 2.0, 80.0);
    float angle = hash(gl_FragCoord.xy) * 6.2831853;
    float occlusion = 0.0;
    for (int i = 0; i < SAMPLES; i++) {
        float t = (float(i) + 0.5) / float(SAMPLES);
        float a = angle + float(i) * 2.3999632;
        vec2 offset = vec2(cos(a), sin(a)) * reach * sqrt(t) * texel;
        vec3 q = viewAt(uv + offset);
        vec3 v = q - p;
        float dist = length(v);
        float cosine = dot(n, v) / max(dist, 0.0001);
        // Ignore what is right beside the point (the game's smooth lighting already shades block corners)
        // and what is too far to matter.
        occlusion += max(cosine - 0.2, 0.0) * smoothstep(0.3, 0.9, dist) * (1.0 - smoothstep(RADIUS * 0.7, RADIUS * 1.5, dist));
    }
    float ao = 1.0 - occlusion / float(SAMPLES) * STRENGTH;
    // Nothing far away: the pixels there are larger than the corners this looks for.
    ao = mix(ao, 1.0, smoothstep(48.0, 96.0, -p.z));
    gl_FragColor = vec4(vec3(clamp(ao, 0.0, 1.0)), 1.0);
}

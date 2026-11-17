// Light shafts from the sun, at a quarter size: the open sky in the depth picture smeared
// along lines towards the sun, so it streams past trees and hills that stand in front of it.
uniform sampler2D depthTex;
uniform vec2 sunPos;           // the sun on the screen, 0..1 (may be off screen)

varying vec2 uv;

const int SAMPLES = 40;
const float DENSITY = 0.85;
const float DECAY = 0.965;

void main() {
    vec2 step = (sunPos - uv) * (DENSITY / float(SAMPLES));
    vec2 p = uv;
    float light = 0.0;
    float weight = 1.0;
    for (int i = 0; i < SAMPLES; i++) {
        p += step;
        if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) {
            break;
        }
        light += (texture2D(depthTex, p).r >= 1.0 ? 1.0 : 0.0) * weight;
        weight *= DECAY;
    }
    gl_FragColor = vec4(vec3(light / float(SAMPLES)), 1.0);
}

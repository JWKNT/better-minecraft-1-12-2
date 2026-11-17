// Darkens the picture by the ambient occlusion (blended as a multiply). The half-size occlusion
// is brought up to full size from its four nearest pixels, each weighed by how close its depth
// is to this pixel's, so it does not bleed across the edge of a nearer object.
uniform sampler2D aoTex;
uniform sampler2D depthTex;
uniform vec2 aoTexel;          // one pixel of the half-size picture

varying vec2 uv;

void main() {
    float d0 = texture2D(depthTex, uv).r;
    vec2 base = (floor(uv / aoTexel - 0.5) + 0.5) * aoTexel;
    float sum = 0.0, weight = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 p = base + vec2(float(i - (i / 2) * 2), float(i / 2)) * aoTexel;
        float w = 1.0 / (0.0003 + abs(texture2D(depthTex, p).r - d0) * 300.0);
        sum += texture2D(aoTex, p).r * w;
        weight += w;
    }
    gl_FragColor = vec4(vec3(sum / weight), 1.0);
}

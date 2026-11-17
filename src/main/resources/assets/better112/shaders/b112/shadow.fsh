uniform sampler2D tex;

varying vec2 texcoord;

void main() {
#ifdef ALPHA_TEST
    if (texture2D(tex, texcoord).a < 0.1) {
        discard;
    }
#endif
    gl_FragColor = vec4(1.0);
}

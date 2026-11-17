// The world seen from the sun, depth only, for shadows.

attribute vec3 chunkOffsetA;   // the chunk section's origin from the shadow map's centre
uniform mat4 shadowMatrix;

varying vec2 texcoord;

void main() {
    vec4 p = shadowMatrix * vec4(gl_Vertex.xyz + chunkOffsetA, 1.0);
    p.xy = distortShadow(p.xy);
    gl_Position = p;
    texcoord = gl_MultiTexCoord0.st;
}

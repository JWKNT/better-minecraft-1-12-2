// Terrain: the chunks of the world, layer by layer. Replaces the fixed pipeline the game
// would use (texture x vertex colour x light map, then fog) and adds sun light, shadows and
// swaying plants.

#ifdef OFFSET_ATTRIBUTE
attribute vec3 chunkOffsetA;   // as below, given as a constant vertex attribute instead of a uniform
#define chunkOffset (chunkOffsetUniform + chunkOffsetA)
uniform vec3 chunkOffsetUniform;
#else
uniform vec3 chunkOffset;      // where the chunk being drawn sits, from the render origin (0 when the game moves the matrix itself)
#endif
uniform mat4 viewInverse;      // camera space -> render-origin space
uniform vec3 originWrap;       // render origin, wrapped, for noise anchored in the world
uniform float time;            // seconds
uniform float wind;            // 1 calm, more in rain and storms

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 tint;
varying vec3 viewPos;
varying vec3 worldPos;         // from the render origin

#ifdef WAVING
vec3 sway(vec3 p, float amount) {
    float t = time;
    float a = sin(t * 1.7 + p.x * 0.55 + p.z * 0.35) * 0.55 + sin(t * 2.9 + p.x * 1.1 - p.z * 0.85) * 0.30
            + sin(t * 4.6 - p.x * 0.35 + p.z * 1.6) * 0.15;
    float b = sin(t * 1.3 + p.z * 0.65 - p.x * 0.25) * 0.6 + sin(t * 3.3 - p.z * 1.25 + p.x * 0.45) * 0.4;
    return vec3(a, b * 0.15, b) * amount * wind;
}
#endif

void main() {
    texcoord = gl_MultiTexCoord0.st;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).st;
    tint = gl_Color;
    // The game grows every section by a millionth about its centre so neighbouring sections overlap and
    // no hairline gap opens between them; done here too, as the section is placed by chunkOffset.
    vec3 local = (gl_Vertex.xyz - 8.0) * 1.000001 + 8.0 + chunkOffset;
#ifdef WAVING
    // How far this vertex may sway, marked in its colour's alpha while the chunk was built (see Sway).
    float steps = floor((1.0 - gl_Color.a) * 255.0 + 0.5);
    if (steps > 0.5) {
        vec3 rel = (viewInverse * (gl_ModelViewMatrix * vec4(local, 1.0))).xyz;
        local += sway(rel + originWrap, steps * (0.09 / 23.0));
    }
#endif
#ifndef LAYER_TRANSLUCENT
    tint.a = 1.0; // the alpha of terrain outside the translucent layer carries the sway mark, not a colour
#endif
    vec4 vp = gl_ModelViewMatrix * vec4(local, 1.0);
    viewPos = vp.xyz;
    worldPos = (viewInverse * vp).xyz;
    gl_Position = gl_ProjectionMatrix * vp;
}

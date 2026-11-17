// Shared by every program of the mod's shaders. Inserted after the #version line and the
// #defines the game picks for the program (see better112.shader.Program).

// Material kinds of the block atlas, as AtlasMask writes them.
const float KIND_LEAVES = 1.0;
const float KIND_PLANT = 2.0;        // lower or only part of a plant: its top sways
const float KIND_PLANT_UPPER = 3.0;  // upper half of a two-block plant: its bottom sways as the lower half's top
const float KIND_VINE = 4.0;
const float KIND_WATER = 5.0;
const float KIND_GLOW = 6.0;         // lava, fire, glowstone and the like: not darkened by shadows

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Shadow map distortion: more of the map's pixels near the player, fewer far away.
vec2 distortShadow(vec2 p) {
    return p / (length(p) * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT));
}

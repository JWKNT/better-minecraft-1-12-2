// Terrain, per pixel. With every effect off this is exactly what the game draws: the block
// texture times the vertex colour (biome tint, shading, ambient occlusion) times the light map,
// then the game's fog.

// 1: show sunlit (white) and shade (black) instead of the picture.
#define DEBUG_VIEW 0

uniform sampler2D tex;
uniform sampler2D lightmap;
uniform float fogMode;         // 0 none, 1 linear, 2 exp, 3 exp2 (as the game set it up)
uniform mat4 viewInverse;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 tint;
varying vec3 viewPos;
varying vec3 worldPos;

#ifdef MASK
uniform sampler2D maskTex;
#endif

#ifdef LIGHTING
uniform vec3 lightDir;         // towards the sun (or the moon at night), camera space
uniform float sunElev;         // sine of the sun's height, > 0 by day
uniform float tintAmount;      // 0 the game's own colours .. 1 the full tint
uniform float shadowStrength;  // 0 no shadows (night, rain, sun below the horizon) .. 1
uniform float shadeSky;        // the sky light level shade keeps, as a fraction
uniform vec3 torchTint;        // colour of block light, about 1

// Colour of direct light: warm when the sun is low, nearly white at noon, cool under the moon.
vec3 directColor() {
    float high = smoothstep(0.03, 0.55, abs(sunElev));
    vec3 c = sunElev > 0.0 ? mix(vec3(1.22, 0.88, 0.64), vec3(1.05, 1.0, 0.94), high) : vec3(0.80, 0.90, 1.16);
    return mix(vec3(1.0), c, tintAmount);
}

// Colour of shade: the open sky's blue.
vec3 shadeColor() {
    vec3 c = sunElev > 0.0 ? vec3(0.86, 0.94, 1.12) : vec3(0.86, 0.92, 1.08);
    return mix(vec3(1.0), c, tintAmount);
}
#endif

#ifdef SHADOWS
uniform sampler2DShadow shadowTex;
uniform mat4 shadowMatrix;     // shadow space from (render-origin space + shadowShift)
uniform vec3 shadowShift;
uniform float shadowTexel;     // 1 / shadow map size
#endif

#ifdef WATER
uniform sampler2D sceneDepth;  // the depth of everything drawn before water
uniform sampler2D sceneColor;  // and its colour
uniform vec2 screenSize;
uniform vec3 skyColor;
uniform vec3 horizonColor;
uniform vec3 originWrap;
uniform float time;
uniform float eyeInWater;
uniform vec3 lightDirWorld;
#endif

float fogFactor() {
    float z = length(viewPos);
    if (fogMode < 0.5) {
        return 1.0;
    } else if (fogMode < 1.5) {
        return clamp((gl_Fog.end - z) * gl_Fog.scale, 0.0, 1.0);
    } else if (fogMode < 2.5) {
        return clamp(exp(-gl_Fog.density * z), 0.0, 1.0);
    }
    float d = gl_Fog.density * z;
    return clamp(exp(-d * d), 0.0, 1.0);
}

#ifdef SHADOWS
// 1 lit, 0 in shadow. The normal is the surface's (or straight up for leaves and plants).
float sunVisibility(vec3 normalWorld, float facing) {
    vec3 p = worldPos + shadowShift;
    vec4 s0 = shadowMatrix * vec4(p, 1.0);
    // How many blocks one pixel of the shadow picture covers here. The distortion packs pixels near the
    // centre and stretches them going out, most along the line from the centre: that is the size that counts.
    float stretch = length(s0.xy) * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
    float texelWorld = 2.0 * SHADOW_RADIUS / SHADOW_SIZE * stretch * stretch / (1.0 - SHADOW_DISTORT);
    // Read the picture a little off the surface, along its normal, so a surface does not shadow itself.
    p += normalWorld * (texelWorld * 1.5 + 0.02);
    vec4 s = shadowMatrix * vec4(p, 1.0);
    float edge = max(abs(s.x), abs(s.y));
    if (edge >= 1.0) {
        return 1.0;
    }
    vec3 c = vec3(distortShadow(s.xy), s.z) * 0.5 + 0.5;
    float bias = (0.03 + texelWorld * (0.6 + 2.0 * (1.0 - facing))) / (2.0 * SHADOW_DEPTH);
    float r = shadowTexel * 0.8;
    float lit = shadow2D(shadowTex, vec3(c.xy + vec2(-r, -r), c.z - bias)).r
              + shadow2D(shadowTex, vec3(c.xy + vec2(r, -r), c.z - bias)).r
              + shadow2D(shadowTex, vec3(c.xy + vec2(-r, r), c.z - bias)).r
              + shadow2D(shadowTex, vec3(c.xy + vec2(r, r), c.z - bias)).r;
    lit *= 0.25;
    // Fade out towards the edge of the picture rather than stopping at a line.
    return mix(lit, 1.0, smoothstep(0.85, 1.0, edge));
}
#endif

#ifdef WATER
float waterHeight(vec2 p) {
    float t = time;
    return sin(dot(p, vec2(0.82, 0.57)) * 1.9 + t * 1.6) * 0.30
         + sin(dot(p, vec2(-0.45, 0.89)) * 2.7 + t * 2.1) * 0.20
         + sin(dot(p, vec2(0.96, -0.27)) * 4.3 + t * 2.9) * 0.12
         + sin(dot(p, vec2(-0.71, -0.70)) * 7.1 + t * 3.7) * 0.06;
}

vec3 waterNormal(vec2 p) {
    float e = 0.05;
    float h = waterHeight(p);
    float dx = (waterHeight(p + vec2(e, 0.0)) - h) / e;
    float dz = (waterHeight(p + vec2(0.0, e)) - h) / e;
    return normalize(vec3(-dx * 0.06, 1.0, -dz * 0.06));
}

float linearDepth(float d) {
    float ndc = d * 2.0 - 1.0;
    return gl_ProjectionMatrix[3][2] / (ndc + gl_ProjectionMatrix[2][2]);
}

vec3 skyReflection(vec3 dirWorld) {
    vec3 c = mix(horizonColor, skyColor, smoothstep(-0.05, 0.45, dirWorld.y));
#ifdef LIGHTING
    float sun = pow(max(dot(dirWorld, lightDirWorld), 0.0), 350.0) * 6.0 + pow(max(dot(dirWorld, lightDirWorld), 0.0), 18.0) * 0.25;
    c += directColor() * sun * shadowStrength;
#endif
    return c;
}

// Reflection traced through the picture drawn so far; alpha 0 where the ray leaves it.
vec4 traceReflection(vec3 start, vec3 dir) {
    float stepLen = 0.6;
    vec3 p = start + dir * 0.2;
    vec3 last = p;
    for (int i = 0; i < SSR_STEPS; i++) {
        last = p;
        p += dir * stepLen;
        stepLen *= 1.22;
        vec4 clip = gl_ProjectionMatrix * vec4(p, 1.0);
        if (clip.w <= 0.0) {
            break;
        }
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
            break;
        }
        float scene = linearDepth(texture2D(sceneDepth, uv).r);
        float ray = -p.z;
        if (ray > scene && ray - scene < stepLen * 2.5 + 0.5) {
            // Halve the step back and forth a few times to find the surface.
            vec3 a = last, b = p;
            for (int j = 0; j < 5; j++) {
                vec3 m = (a + b) * 0.5;
                vec4 mc = gl_ProjectionMatrix * vec4(m, 1.0);
                vec2 muv = mc.xy / mc.w * 0.5 + 0.5;
                if (-m.z > linearDepth(texture2D(sceneDepth, muv).r)) {
                    b = m;
                } else {
                    a = m;
                }
            }
            vec4 bc = gl_ProjectionMatrix * vec4(b, 1.0);
            vec2 buv = bc.xy / bc.w * 0.5 + 0.5;
            if (texture2D(sceneDepth, buv).r >= 1.0) {
                break; // the sky: the sky colour below does better
            }
            vec2 fade = smoothstep(0.0, 0.08, buv) * (1.0 - smoothstep(0.92, 1.0, buv));
            return vec4(texture2D(sceneColor, buv).rgb, fade.x * fade.y);
        }
    }
    return vec4(0.0);
}
#endif

void main() {
    vec4 albedo = texture2D(tex, texcoord) * tint;
    float kind = 0.0;
#ifdef MASK
    kind = floor(texture2D(maskTex, texcoord).r * 255.0 + 0.5);
#endif
    vec3 normalView = normalize(cross(dFdx(viewPos), dFdy(viewPos)));
    vec3 normalWorld = (viewInverse * vec4(normalView, 0.0)).xyz;

#ifdef LIGHTING
    bool foliage = kind >= KIND_LEAVES && kind <= KIND_VINE;
    float facing = foliage ? 1.0 : dot(normalView, lightDir);
    float direct = clamp(facing * 3.0, 0.0, 1.0);
#ifdef SHADOWS
    if (direct > 0.0 && shadowStrength > 0.0 && lmcoord.y > 0.07) {
        direct *= sunVisibility(foliage ? vec3(0.0, 1.0, 0.0) : normalWorld, foliage ? 1.0 : clamp(facing, 0.0, 1.0));
    }
#endif
    if (kind == KIND_GLOW) {
        direct = 1.0;
    }
    float shade = (1.0 - direct) * shadowStrength;
    // Sky light level kept in shade: the light map is read again with a lower sky level.
    float skyLevel = (lmcoord.y * 16.0 - 0.5) * mix(1.0, shadeSky, shade);
    vec3 blockOnly = texture2D(lightmap, vec2(lmcoord.x, 0.5 / 16.0)).rgb;
    vec3 total = texture2D(lightmap, vec2(lmcoord.x, (skyLevel + 0.5) / 16.0)).rgb;
    vec3 sky = max(total - blockOnly, 0.0);
    vec3 skyTint = mix(vec3(1.0), mix(directColor(), shadeColor(), 1.0 - direct), shadowStrength);
    vec3 light = blockOnly * torchTint + sky * skyTint;
#else
    vec3 light = texture2D(lightmap, lmcoord).rgb;
#endif

    vec4 color = vec4(albedo.rgb * light, albedo.a);

#ifdef WATER
    if (kind == KIND_WATER && eyeInWater < 0.5) {
        vec3 n = normalWorld;
        if (n.y > 0.9) {
            n = waterNormal(worldPos.xz + originWrap.xz);
        }
        vec3 nView = normalize((gl_ModelViewMatrix * vec4(n, 0.0)).xyz);
        vec3 v = normalize(-viewPos);
        float cosTheta = max(dot(nView, v), 0.0);
        float fresnel = 0.02 + 0.98 * pow(1.0 - cosTheta, 5.0);
        vec3 rView = reflect(-v, nView);
        vec3 rWorld = (viewInverse * vec4(rView, 0.0)).xyz;
        vec3 reflection = skyReflection(rWorld) * clamp(lmcoord.y * 1.3 - 0.2, 0.15, 1.0);
#if SSR_STEPS > 0
        vec4 traced = traceReflection(viewPos, rView);
        reflection = mix(reflection, traced.rgb, traced.a);
#endif
        vec2 screen = gl_FragCoord.xy / screenSize;
        float behind = linearDepth(texture2D(sceneDepth, screen).r);
        float thickness = max(behind - (-viewPos.z), 0.0);
        float absorb = 1.0 - exp(-thickness * 0.22);
        vec3 body = albedo.rgb * light * 0.85;
        float opacity = clamp(mix(0.30, 0.92, absorb), 0.0, 1.0);
        fresnel = clamp(fresnel * 1.15, 0.0, 1.0);
        float a = 1.0 - (1.0 - opacity) * (1.0 - fresnel);
        vec3 rgb = (body * opacity * (1.0 - fresnel) + reflection * fresnel) / max(a, 0.001);
        color = vec4(rgb, a);
    }
#endif

    vec3 fogColor = gl_Fog.color.rgb;
#ifdef LIGHTING
    // Towards a low sun the haze takes the sun's warm colour, as the sky behind it does at sunrise and sunset.
    if (sunElev > 0.0) {
        float toward = max(dot(normalize(viewPos), lightDir), 0.0);
        float low = 1.0 - smoothstep(0.05, 0.45, sunElev);
        fogColor = mix(fogColor, fogColor * directColor() * 1.3, pow(toward, 5.0) * low * shadowStrength * tintAmount);
    }
#endif
    color.rgb = mix(fogColor, color.rgb, fogFactor());
#if DEBUG_VIEW == 1 && defined(LIGHTING)
    color.rgb = vec3(direct);
#endif
    gl_FragColor = color;
}

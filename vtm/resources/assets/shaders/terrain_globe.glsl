#ifdef GLES
precision highp float;
#endif
uniform mat4 u_mvp;
uniform vec4 u_color;
uniform float u_alpha;
uniform vec3 u_light;
uniform float u_zlimit;
uniform float u_globeRadius;
// Tile geographic bounds for Mercator→ECEF sphere warp
uniform float u_tileLonMin, u_tileLonRange;
uniform float u_tileLatMax, u_tileLatRange;
attribute vec4 a_pos;
attribute vec2 a_normal;
varying vec4 color;
varying vec3 v_normal;
varying vec3 v_worldPos;

const float PI = 3.14159265359;
const float DEG_TO_RAD = PI / 180.0;

void main() {
    // Sphere warp: convert Mercator tile-local to ECEF position.
    // a_pos.xy are Mercator tile-local (0..TILE_SCALE_MAX).
    // a_pos.z is the radial elevation offset from the sphere surface.
    float lon = (u_tileLonMin + (a_pos.x / 4096.0) * u_tileLonRange) * DEG_TO_RAD;
    float lat = (u_tileLatMax - (a_pos.y / 4096.0) * u_tileLatRange) * DEG_TO_RAD;

    float R = u_globeRadius + a_pos.z;
    float cosLat = cos(lat);
    vec3 ecefPos = vec3(
        R * cosLat * cos(lon),
        R * cosLat * sin(lon),
        R * sin(lat)
    );

    vec4 pos = vec4(ecefPos, 1.0);
    pos.z *= u_alpha;
    gl_Position = u_mvp * pos;

    // Pass world-space position and normal for atmosphere
    v_worldPos = ecefPos;

    // Reconstruct face normal from packed 2-byte encoding
    vec2 enc = (a_normal / 255.0);
    vec3 r_norm;
    r_norm.xy = enc * 2.0 - 1.0;
    float dir = -1.0 + (2.0 * abs(mod(a_normal.x, 2.0)));
    r_norm.z = dir * sqrt(clamp(1.0 - (r_norm.x * r_norm.x + r_norm.y * r_norm.y), 0.0, 1.0));
    r_norm = normalize(r_norm);
    v_normal = r_norm;

    // Directional lighting
    float l = dot(r_norm, normalize(u_light));
    l = 0.6 + l * 0.4;

    // Height-based color: low = dark earth, high = light/white
    float h = clamp(a_pos.z / u_zlimit, 0.0, 1.0);

    vec3 lowColor  = vec3(0.35, 0.25, 0.15);
    vec3 midColor  = vec3(0.55, 0.40, 0.25);
    vec3 highColor = vec3(0.85, 0.78, 0.65);
    vec3 baseColor;
    if (h < 0.5) {
        baseColor = mix(lowColor, midColor, h * 2.0);
    } else {
        baseColor = mix(midColor, highColor, (h - 0.5) * 2.0);
    }

    color = vec4(baseColor * l, u_color.a) * u_alpha;
}

$$

#ifdef GLES
precision highp float;
#endif
uniform vec3 u_cameraPos;
uniform vec4 u_atmosphereColor;
uniform float u_fogDensity;
varying vec4 color;
varying vec3 v_normal;
varying vec3 v_worldPos;

void main() {
    vec3 viewDir = normalize(u_cameraPos - v_worldPos);
    float ndotv = abs(dot(normalize(v_normal), viewDir));
    float limbFactor = 1.0 - ndotv;
    float fog = smoothstep(0.15, 0.85, limbFactor) * u_fogDensity;

    vec3 terrainColor = mix(color.rgb, u_atmosphereColor.rgb, fog);
    gl_FragColor = vec4(terrainColor, color.a);
}

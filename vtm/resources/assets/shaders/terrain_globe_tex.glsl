#ifdef GLES
precision highp float;
#endif
uniform mat4 u_mvp;
uniform vec4 u_color;
uniform float u_alpha;
uniform vec3 u_light;
uniform float u_zlimit;
uniform vec3 u_cameraPos;
attribute vec4 a_pos;
attribute vec2 a_normal;
varying vec4 color;
varying vec2 v_texcoord;
varying vec3 v_normal;
varying vec3 v_worldPos;

void main() {
    vec4 pos = a_pos;
    pos.z *= u_alpha;
    gl_Position = u_mvp * pos;

    // Compute UV from vertex position (tile-local coords 0..4096 for Mercator).
    // For globe ECEF-relative coords, this is approximate; raster texture UV
    // accuracy depends on the mesh being reasonably tile-shaped in ECEF space.
    v_texcoord = a_pos.xy / 4096.0;

    // Pass world-space position and normal for atmosphere
    v_worldPos = a_pos.xyz;

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
    l = 0.6 + l * 0.4; // softer: range [0.2, 1.0]

    // Height-based color: low = dark earth, high = light/white
    float h = clamp(a_pos.z / u_zlimit, 0.0, 1.0);

    // Gradient: dark brown (low) -> warm brown (mid) -> light tan (high)
    vec3 lowColor  = vec3(0.35, 0.25, 0.15);  // dark brown
    vec3 midColor  = vec3(0.55, 0.40, 0.25);  // warm brown
    vec3 highColor = vec3(0.85, 0.78, 0.65);  // light tan
    vec3 baseColor;
    if (h < 0.5) {
        baseColor = mix(lowColor, midColor, h * 2.0);
    } else {
        baseColor = mix(midColor, highColor, (h - 0.5) * 2.0);
    }

    // Apply lighting to the height-based color
    color = vec4(baseColor * l, u_color.a) * u_alpha;
}

$$

#ifdef GLES
precision highp float;
#endif
uniform sampler2D u_tex;
uniform float u_texMix;
uniform vec3 u_cameraPos;
uniform vec4 u_atmosphereColor;
uniform float u_fogDensity;
varying vec4 color;
varying vec2 v_texcoord;
varying vec3 v_normal;
varying vec3 v_worldPos;

void main() {
    // Atmosphere limb fog using view direction
    vec3 viewDir = normalize(u_cameraPos - v_worldPos);
    float ndotv = abs(dot(normalize(v_normal), viewDir));
    float limbFactor = 1.0 - ndotv;

    float fog = smoothstep(0.15, 0.85, limbFactor) * u_fogDensity;

    vec4 texColor = texture2D(u_tex, v_texcoord);
    // Mix procedural terrain color with raster texture
    vec3 terrainColor = mix(color.rgb, texColor.rgb, u_texMix);
    terrainColor = mix(terrainColor, u_atmosphereColor.rgb, fog);

    gl_FragColor = vec4(terrainColor, color.a);
}

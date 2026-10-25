#version 450
// PyroWave decodes to three planes (Y, Cb, Cr). The host encodes left-cosited 4:2:0 chroma, so shift
// chroma sampling by a quarter chroma texel to line it up with centre-sampled luma. Range and matrix
// follow the stream (Delos: push constants).
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 frag;

layout(set = 0, binding = 0) uniform sampler2D u_y;
layout(set = 0, binding = 1) uniform sampler2D u_cb;
layout(set = 0, binding = 2) uniform sampler2D u_cr;

layout(push_constant) uniform Csc {
    int fullRange;
    // 0 = BT.601, 1 = BT.709, 2 = BT.2020
    int matrix;
} csc;

void main() {
    vec2 cuv = v_uv;
    float cw = float(textureSize(u_cb, 0).x);
    if (cw < float(textureSize(u_y, 0).x)) {
        cuv.x += 0.25 / cw;
    }

    float y = texture(u_y, v_uv).r;
    float cb = texture(u_cb, cuv).r - 128.0 / 255.0;
    float cr = texture(u_cr, cuv).r - 128.0 / 255.0;
    if (csc.fullRange == 0) {
        y = (y - 16.0 / 255.0) * (255.0 / 219.0);
        cb *= 255.0 / 224.0;
        cr *= 255.0 / 224.0;
    }

    // Kr / Kb of the matrix
    vec2 k = csc.matrix == 0 ? vec2(0.299, 0.114) : (csc.matrix == 2 ? vec2(0.2627, 0.0593) : vec2(0.2126, 0.0722));
    float kg = 1.0 - k.x - k.y;
    vec3 rgb = vec3(
        y + 2.0 * (1.0 - k.x) * cr,
        y - (2.0 * k.y * (1.0 - k.y) / kg) * cb - (2.0 * k.x * (1.0 - k.x) / kg) * cr,
        y + 2.0 * (1.0 - k.y) * cb
    );
    frag = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}

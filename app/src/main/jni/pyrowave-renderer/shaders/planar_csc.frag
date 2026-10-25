#version 450
// PyroWave decodes to three planes (Y, Cb, Cr). The host averages each 2x2 quad for 4:2:0 chroma
// (centre siting, as the official client treats it), so chroma samples line up without an offset.
// Range, matrix and bit depth follow the stream (Delos: push constants). HDR streams are PQ BT.2020
// and go to an HDR10 swapchain as they are.
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 frag;

layout(set = 0, binding = 0) uniform sampler2D u_y;
layout(set = 0, binding = 1) uniform sampler2D u_cb;
layout(set = 0, binding = 2) uniform sampler2D u_cr;

layout(push_constant) uniform Csc {
    int fullRange;
    // 0 = BT.601, 1 = BT.709, 2 = BT.2020
    int matrix;
    // R16 planes: normalised like FFmpeg's 16-bit formats (chroma midpoint 0.5, limited range x256)
    int sixteenBit;
} csc;

void main() {
    float unit = csc.sixteenBit != 0 ? 256.0 / 65535.0 : 1.0 / 255.0;
    float mid = csc.sixteenBit != 0 ? 0.5 : 128.0 / 255.0;
    float y = texture(u_y, v_uv).r;
    float cb = texture(u_cb, v_uv).r - mid;
    float cr = texture(u_cr, v_uv).r - mid;
    if (csc.fullRange == 0) {
        y = (y - 16.0 * unit) / (219.0 * unit);
        cb /= 224.0 * unit;
        cr /= 224.0 * unit;
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

#version 450
// Delos: packs each pixel's decoded (Y, Cb, Cr) into one RGBA16F image for the P010 HDR output (see
// "YUV output" in pyrowave_renderer.cpp), which OpenGL ES copies into P010 unconverted. 4:2:0 chroma is
// repeated over its 2x2 block. Values become 10-bit code values normalised to 1023: the decoder
// normalises 16-bit samples with the 10 bits in the high bits.
layout(location = 0) out vec4 emit;

layout(set = 0, binding = 0) uniform sampler2D u_y;
layout(set = 0, binding = 1) uniform sampler2D u_cb;
layout(set = 0, binding = 2) uniform sampler2D u_cr;

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    const float scale = 65535.0 / (64.0 * 1023.0);
    vec3 ycc = vec3(texelFetch(u_y, texel, 0).r, texelFetch(u_cb, texel / 2, 0).r, texelFetch(u_cr, texel / 2, 0).r);
    emit = vec4(ycc * scale, 1.0);
}

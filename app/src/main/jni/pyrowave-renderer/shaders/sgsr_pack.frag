#version 450
// Convert the SGSR RGB result back to limited-range BT.2020 YCbCr code values for the existing
// RGBA16F-to-P010 bridge. The RGB values remain PQ encoded throughout this operation.
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 emit;
layout(set = 0, binding = 0) uniform sampler2D u_image;
void main() {
    vec3 rgb=texture(u_image,v_uv).rgb;
    const float kr=.2627,kb=.0593,kg=1.0-kr-kb;
    float y=kr*rgb.r+kg*rgb.g+kb*rgb.b;
    float cb=(rgb.b-y)/(2.0*(1.0-kb));
    float cr=(rgb.r-y)/(2.0*(1.0-kr));
    vec3 limited=vec3(16.0/1023.0+y*876.0/1023.0,
                      512.0/1023.0+cb*896.0/1023.0,
                      512.0/1023.0+cr*896.0/1023.0);
    emit=vec4(clamp(limited,0.0,1.0),1.0);
}

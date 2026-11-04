// Exact approved stream sampling, with a live FP16 mip pyramid. At most three video samples.
layout(set = 0, binding = 0) uniform sampler2D u_image;
layout(push_constant) uniform Arrival {
    vec4 pose; // scaleX, scaleY, skew tangent, source-pixel warp
    vec4 fx;   // blur control, source-pixel chromatic split, source width, source height
} arrival;
vec3 blurredStream(vec2 p) {
    return textureLod(u_image, clamp(p, .5/arrival.fx.zw, 1.0-.5/arrival.fx.zw),
                      log2(1.0+arrival.fx.x*float(textureSize(u_image, 0).x)/arrival.fx.z)).rgb;
}
vec3 arrivalColor(vec2 uv) {
    vec2 p = uv-.5;
    p.y -= p.x*arrival.pose.z;
    p /= arrival.pose.xy;
    p += vec2(sin(p.y*8.0)*cos(p.x*3.0), sin(p.x*7.0)*cos(p.y*3.0))
         * arrival.pose.w*.45/arrival.fx.zw;
    p += .5;
    if (arrival.fx.y < .05) return blurredStream(p);
    vec2 split = vec2(1.0, .2)*arrival.fx.y/arrival.fx.zw;
    return vec3(blurredStream(p-split).r, blurredStream(p).g, blurredStream(p+split).b);
}

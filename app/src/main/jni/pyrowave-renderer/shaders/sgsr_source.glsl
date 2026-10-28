// Shared decoded-frame conversion. RGB stays in its encoded transfer domain, including PQ.
vec3 source(ivec2 q) {
    q=clamp(q,ivec2(0),p.inputSize-1);
    vec2 uv=(vec2(q)+.5)/vec2(p.inputSize);
    float unit=p.sixteenBit!=0?256.0/65535.0:1.0/255.0;
    float mid=p.sixteenBit!=0?.5:128.0/255.0;
    float y=texelFetch(u_y,q,0).r;
    float cb=texture(u_cb,uv).r-mid,cr=texture(u_cr,uv).r-mid;
    if(p.fullRange==0){y=(y-16.0*unit)/(219.0*unit);cb/=224.0*unit;cr/=224.0*unit;}
    vec2 k=p.matrix==0?vec2(.299,.114):(p.matrix==2?vec2(.2627,.0593):vec2(.2126,.0722));
    float kg=1.0-k.x-k.y;
    return clamp(vec3(y+2.0*(1.0-k.x)*cr,
        y-(2.0*k.y*(1.0-k.y)/kg)*cb-(2.0*k.x*(1.0-k.x)/kg)*cr,
        y+2.0*(1.0-k.y)*cb),0.0,1.0);
}
vec3 sourceBilinear(vec2 uv) {
    vec2 pos=uv*vec2(p.inputSize)-.5;
    ivec2 q=ivec2(floor(pos));
    vec2 f=fract(pos);
    return mix(mix(source(q),source(q+ivec2(1,0)),f.x),
               mix(source(q+ivec2(0,1)),source(q+ivec2(1,1)),f.x),f.y);
}

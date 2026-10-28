#version 450

layout(binding = 1) uniform sampler2D texSampler;

layout(location = 0) in vec2 fragTexCoord;
layout(location = 1) in vec3 fragNormal;
layout(location = 2) in float fragOpacity;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 baseColor = texture(texSampler, fragTexCoord);
    outColor = vec4(baseColor.rgb, baseColor.a * fragOpacity);
}

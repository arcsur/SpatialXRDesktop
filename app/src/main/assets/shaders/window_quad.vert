#version 450
#extension GL_EXT_multiview : enable

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;
layout(location = 2) in vec3 inNormal;

layout(binding = 0) uniform UniformBufferObject {
    mat4 model;
    mat4 view[2];
    mat4 proj[2];
} ubo;

layout(location = 0) out vec2 fragTexCoord;
layout(location = 1) out vec3 fragNormal;

void main() {
    uint viewIndex = gl_ViewIndex;
    gl_Position = ubo.proj[viewIndex] * ubo.view[viewIndex] * ubo.model * vec4(inPosition, 1.0);
    fragTexCoord = inTexCoord;
    fragNormal = mat3(ubo.model) * inNormal;
}

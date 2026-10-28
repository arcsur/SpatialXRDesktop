#version 450
#extension GL_EXT_multiview : enable

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;

layout(binding = 0) uniform FrameUBO {
    mat4 view[2];
    mat4 proj[2];
} ubo;

layout(location = 0) out vec4 fragColor;

void main() {
    uint viewIndex = gl_ViewIndex;
    gl_Position = ubo.proj[viewIndex] * ubo.view[viewIndex] * vec4(inPosition, 1.0);
    fragColor = inColor;
}

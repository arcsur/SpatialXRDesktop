#version 450
#extension GL_EXT_multiview : enable

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;
layout(location = 2) in vec3 inNormal;

layout(binding = 0) uniform UniformBufferObject {
    mat4 model;
    mat4 view[2];
    mat4 proj[2];
    float cylinderRadius; // Default 2.0m
    float totalFovRad;    // 90 deg = 1.570796 rad
    float opacity;        // 1.0 for active, 0.15 for dimmed windows
} ubo;

layout(location = 0) out vec2 fragTexCoord;
layout(location = 1) out vec3 fragNormal;
layout(location = 2) out float fragOpacity;

void main() {
    uint viewIndex = gl_ViewIndex;

    // Apply cylindrical projection if cylinderRadius > 0
    vec3 pos = inPosition;
    if (ubo.cylinderRadius > 0.01) {
        float angle = (inTexCoord.x - 0.5) * ubo.totalFovRad;
        pos.x = ubo.cylinderRadius * sin(angle);
        pos.z = -ubo.cylinderRadius * cos(angle) + ubo.cylinderRadius;
    }

    gl_Position = ubo.proj[viewIndex] * ubo.view[viewIndex] * ubo.model * vec4(pos, 1.0);
    fragTexCoord = inTexCoord;
    fragNormal = mat3(ubo.model) * inNormal;
    fragOpacity = ubo.opacity;
}

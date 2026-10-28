#version 450
#extension GL_EXT_multiview : enable

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;

layout(binding = 0) uniform WarpUBO {
    mat4 warpMatrix[2]; // Reprojection matrix per eye
} ubo;

layout(location = 0) out vec2 fragTexCoord;

void main() {
    uint viewIndex = gl_ViewIndex;

    // Reproject vertex position using late-latched warp matrix
    vec4 warpedPos = ubo.warpMatrix[viewIndex] * vec4(inPosition, 1.0);
    gl_Position = warpedPos;
    fragTexCoord = inTexCoord;
}

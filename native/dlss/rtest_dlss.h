#pragma once
#include <stdint.h>
// ABI 1. Serialized calls on the render thread. Retirement requires GPU completion.
struct RtestSlImage {
    uint64_t image, view, memory;
    uint32_t width, height, format, layout, usage, reserved;
};
struct RtestSlFrame {
    uint64_t command_buffer;
    uint32_t frame_index, reset;
    float jitter[2], camera_near, camera_far, camera_fov, camera_aspect;
    float camera_position[3], camera_up[3], camera_right[3], camera_forward[3];
    // Row-major storage, row-vector multiplication, unjittered, camera-relative world.
    float world_to_view[16], view_to_world[16], view_to_clip[16], clip_to_view[16];
    float clip_to_previous_clip[16], previous_clip_to_clip[16];
    // color, depth, motion, normals/roughness, diffuse albedo, specular albedo,
    // output color, raw world-unit specular hit distance. All in GENERAL layout.
    RtestSlImage images[8];
};

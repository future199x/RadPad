const std = @import("std");

/// Build script for cross-compiling the bare-metal input math engine
/// targeting aarch64-linux-android for zero-latency controller IME processing.
pub fn build(b: *std.Build) void {
    // -------------------------------------------------------------------------
    // Target Configuration: Cross-compile specifically for aarch64-linux-android
    // -------------------------------------------------------------------------
    const target = b.standardTargetOptions(.{
        .default_target = .{
            .cpu_arch = .aarch64,
            .os_tag = .linux,
            .abi = .android,
        },
    });

    // Default to ReleaseFast: enables aggressive optimizations, inlining,
    // and vectorization while stripping safety panics in the hot loop.
    const optimize = b.standardOptimizeOption(.{
        .preferred_optimize_mode = .ReleaseFast,
    });

    // -------------------------------------------------------------------------
    // Shared Dynamic Library Artifact: libzigengine.so
    // -------------------------------------------------------------------------
    const lib = b.addLibrary(.{
        .linkage = .dynamic,
        .name = "zigengine",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/main.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });

    // Install to zig-out/lib/libzigengine.so
    b.installArtifact(lib);

    // -------------------------------------------------------------------------
    // Unit Testing Step (Executes against native host platform)
    // -------------------------------------------------------------------------
    const unit_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/main.zig"),
            .target = b.resolveTargetQuery(.{}), // Resolve native host architecture
            .optimize = optimize,
        }),
    });

    const run_unit_tests = b.addRunArtifact(unit_tests);
    const test_step = b.step("test", "Execute unit tests verifying math, cones, and state machine");
    test_step.dependOn(&run_unit_tests.step);
}

const std = @import("std");

// ============================================================================
// BITMASK CONSTANTS: HARDWARE CONTROLLER INPUTS (passed from Kotlin layer)
// ============================================================================
pub const FLAG_R2_HOLD: i32 = 1 << 1;   // Right Trigger (R2) -> Second Layer Hold
pub const FLAG_SHIFT: i32 = 1 << 2;     // Left Trigger (L2) -> SHIFT (Hold to uppercase / symbols)
pub const FLAG_CTRL: i32 = 1 << 3;      // Ctrl (Left Stick Down / Tilt)
pub const FLAG_ALT: i32 = 1 << 4;       // Alt (Left Stick Left / Tilt)
pub const FLAG_CAPS_LOCK: i32 = 1 << 5; // Caps Lock (toggled via L3 click)
pub const FLAG_SUPER: i32 = 1 << 7;     // Super / Windows Key (Left Stick Right / Tilt)
pub const FLAG_SELECT: i32 = 1 << 8;    // Right Bumper (R1) -> Select Layer / Character
pub const FLAG_BACK: i32 = 1 << 9;      // Left Bumper (L1) -> Return to Base Layer


// ============================================================================
// SPECIAL FUNCTION KEYS & NAVIGATION CODES (0xF001 .. 0xF016)
// ============================================================================
pub const KEY_F1: u16 = 0xF001;
pub const KEY_F2: u16 = 0xF002;
pub const KEY_F3: u16 = 0xF003;
pub const KEY_F4: u16 = 0xF004;
pub const KEY_F5: u16 = 0xF005;
pub const KEY_F6: u16 = 0xF006;
pub const KEY_F7: u16 = 0xF007;
pub const KEY_F8: u16 = 0xF008;
pub const KEY_F9: u16 = 0xF009;
pub const KEY_F10: u16 = 0xF00A;
pub const KEY_F11: u16 = 0xF00B;
pub const KEY_F12: u16 = 0xF00C;
pub const KEY_HOME: u16 = 0xF00D;
pub const KEY_END: u16 = 0xF00E;
pub const KEY_PGUP: u16 = 0xF00F;
pub const KEY_PGDN: u16 = 0xF010;
pub const KEY_SUPER: u16 = 0xF011;
pub const KEY_DELETE: u16 = 0xF012;
pub const KEY_INSERT: u16 = 0xF013;
pub const KEY_VOL_UP: u16 = 0xF014;
pub const KEY_VOL_DOWN: u16 = 0xF015;
pub const KEY_VOL_MUTE: u16 = 0xF016;

/// Layer transition indicator event packed into upper bits: 0xE000 | layer_id
pub const LAYER_EVENT_MASK: i32 = 0xE000;

// ============================================================================
// BITMASK CONSTANTS: ENGINE OUTPUT FLAGS (packed into upper 16 bits of return value)
// ============================================================================
pub const MOD_SHIFT: i32 = 1 << 16;
pub const MOD_CTRL: i32 = 1 << 17;
pub const MOD_ALT: i32 = 1 << 18;
pub const MOD_SUPER: i32 = 1 << 20;

// ============================================================================
// KINEMATIC TUNING CONSTANTS
// ============================================================================
pub const DEADZONE_ENGAGE: f32 = 0.42;
pub const DEADZONE_ENGAGE_SQ: f32 = DEADZONE_ENGAGE * DEADZONE_ENGAGE;

pub const DEADZONE_RELEASE: f32 = 0.25;
pub const DEADZONE_RELEASE_SQ: f32 = DEADZONE_RELEASE * DEADZONE_RELEASE;

/// 8 Directional Slices (Clockwise starting from North / Up)
pub const Direction = enum(u3) {
    North = 0,
    NorthEast = 1,
    East = 2,
    SouthEast = 3,
    South = 4,
    SouthWest = 5,
    West = 6,
    NorthWest = 7,
};

/// 9 Radial Layers (Base + 8 branches)
pub const LayerId = enum(u8) {
    Base = 0,
    A_H = 1,
    MoreSym = 2,
    I_P = 3,
    Fn = 4,
    Q_X = 5,
    Y_Z = 6,
    NumSym = 7,
    Sys = 8,
};

// ============================================================================
// LAYER MAPPINGS (MATCHING ARCHITECTURE DIAGRAM)
// ============================================================================
// North=0, NorthEast=1, East=2, SouthEast=3, South=4, SouthWest=5, West=6, NorthWest=7
pub const LAYOUT_A_H: [8]u16 = [_]u16{ 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h' };

// SYM 1: North='\'', NE='=', East='.', SE=';', South='\\', SW='/', West=',', NW='-'
pub const LAYOUT_MORE_SYM_1: [8]u16 = [_]u16{ '\'', '=', '.', ';', '\\', '/', ',', '-' };

// SYM 2 (Hold R2): North='`', East=']', West='['
pub const LAYOUT_MORE_SYM_2: [8]u16 = [_]u16{ '`', 0, ']', 0, 0, 0, '[', 0 };

pub const LAYOUT_I_P: [8]u16 = [_]u16{ 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p' };

pub const LAYOUT_FN_1_8: [8]u16 = [_]u16{ KEY_F1, KEY_F2, KEY_F3, KEY_F4, KEY_F5, KEY_F6, KEY_F7, KEY_F8 };

// FN 9-12 (Hold R2): North=F9, NE=F10, East=F11, SE=F12
pub const LAYOUT_FN_9_12: [8]u16 = [_]u16{ KEY_F9, KEY_F10, KEY_F11, KEY_F12, 0, 0, 0, 0 };

pub const LAYOUT_Q_X: [8]u16 = [_]u16{ 'q', 'r', 's', 't', 'u', 'v', 'w', 'x' };

// Y-Z: North='y', NE='z'
pub const LAYOUT_Y_Z: [8]u16 = [_]u16{ 'y', 'z', 0, 0, 0, 0, 0, 0 };

pub const LAYOUT_NUM_1_8: [8]u16 = [_]u16{ '1', '2', '3', '4', '5', '6', '7', '8' };

// NUM 9-0 (Hold R2): North='9', NE='0'
pub const LAYOUT_NUM_9_0: [8]u16 = [_]u16{ '9', '0', 0, 0, 0, 0, 0, 0 };

// SYS: North=PgUp, NE=Vol+, East=End, SE=Del, South=PgDn, SW=Ins, West=Home, NW=Vol-
// Center = KEY_VOL_MUTE (Vol Toggle)
pub const LAYOUT_SYS: [8]u16 = [_]u16{
    KEY_PGUP, KEY_VOL_UP, KEY_END, KEY_DELETE, KEY_PGDN, KEY_INSERT, KEY_HOME, KEY_VOL_DOWN,
};

/// Converts a number or symbol key to its standard US physical keyboard shifted character.
pub fn shiftSymbol(c: u16) u16 {
    return switch (c) {
        '1' => '!',
        '2' => '@',
        '3' => '#',
        '4' => '$',
        '5' => '%',
        '6' => '^',
        '7' => '&',
        '8' => '*',
        '9' => '(',
        '0' => ')',
        '-' => '_',
        '=' => '+',
        '[' => '{',
        ']' => '}',
        '\\' => '|',
        ';' => ':',
        '\'' => '"',
        ',' => '<',
        '.' => '>',
        '/' => '?',
        '`' => '~',
        else => c,
    };
}

// ============================================================================
// DETERMINISTIC RADIAL STATE MACHINE (ZERO ALLOCATIONS)
// ============================================================================
pub const InputStateMachine = struct {
    current_layer: LayerId = .Base,
    use_symmetric_slices: bool = true,
    last_aimed_slice: Direction = .North,
    is_deflected: bool = false,

    pub fn reset(self: *InputStateMachine) void {
        self.current_layer = .Base;
        self.is_deflected = false;
        self.last_aimed_slice = .North;
    }

    /// Computes slice index.
    pub fn calculateSlice(self: *const InputStateMachine, x: f32, y: f32) Direction {
        const rad = std.math.atan2(x, -y);
        var deg = rad * (180.0 / std.math.pi);
        if (deg < 0.0) {
            deg += 360.0;
        }

        if (self.use_symmetric_slices) {
            if (deg >= 337.5 or deg < 22.5) return .North;
            if (deg < 67.5) return .NorthEast;
            if (deg < 112.5) return .East;
            if (deg < 157.5) return .SouthEast;
            if (deg < 202.5) return .South;
            if (deg < 247.5) return .SouthWest;
            if (deg < 292.5) return .West;
            return .NorthWest;
        } else {
            if (deg >= 330.0 or deg < 30.0) return .North;
            if (deg < 60.0) return .NorthEast;
            if (deg < 120.0) return .East;
            if (deg < 150.0) return .SouthEast;
            if (deg < 210.0) return .South;
            if (deg < 240.0) return .SouthWest;
            if (deg < 300.0) return .West;
            return .NorthWest;
        }
    }

    /// Evaluates raw analog stick coordinates and button bitmasks.
    /// Motion alone NEVER emits a character (flick selection removed).
    pub fn process(self: *InputStateMachine, x: f32, y: f32, state_flags: i32) i32 {
        const r_sq = (x * x) + (y * y);
        self.is_deflected = (r_sq >= DEADZONE_ENGAGE_SQ);

        if (self.is_deflected) {
            self.last_aimed_slice = self.calculateSlice(x, y);
        }

        // Left Bumper (L1 / FLAG_BACK) returns to Base Layer
        if ((state_flags & FLAG_BACK) != 0) {
            self.current_layer = .Base;
            return 0;
        }

        // Right Bumper (R1 / FLAG_SELECT) performs selection
        if ((state_flags & FLAG_SELECT) != 0) {
            return self.handleSelection(state_flags);
        }

        return 0;
    }

    pub fn handleSelection(self: *InputStateMachine, state_flags: i32) i32 {
        if (self.current_layer == .Base) {
            // Must be deflected into a slice to select a layer from Base
            if (!self.is_deflected) return 0;

            self.current_layer = switch (self.last_aimed_slice) {
                .North => .A_H,
                .NorthEast => .MoreSym,
                .East => .I_P,
                .SouthEast => .Fn,
                .South => .Q_X,
                .SouthWest => .Y_Z,
                .West => .NumSym,
                .NorthWest => .Sys,
            };
            return LAYER_EVENT_MASK | @as(i32, @intFromEnum(self.current_layer));
        }

        // Inside an active layer:
        // SYS special case: center deadzone is Vol Mute / Toggle!
        if (self.current_layer == .Sys and !self.is_deflected) {
            return @as(i32, KEY_VOL_MUTE);
        }

        // Other layers require stick deflection
        if (!self.is_deflected) return 0;

        const slice_idx: usize = @intFromEnum(self.last_aimed_slice);
        const is_second_layer = (state_flags & FLAG_R2_HOLD) != 0;

        var char_code: u16 = switch (self.current_layer) {
            .A_H => LAYOUT_A_H[slice_idx],
            .I_P => LAYOUT_I_P[slice_idx],
            .Q_X => LAYOUT_Q_X[slice_idx],
            .Y_Z => LAYOUT_Y_Z[slice_idx],
            .MoreSym => if (is_second_layer) LAYOUT_MORE_SYM_2[slice_idx] else LAYOUT_MORE_SYM_1[slice_idx],
            .NumSym => if (is_second_layer) LAYOUT_NUM_9_0[slice_idx] else LAYOUT_NUM_1_8[slice_idx],
            .Fn => if (is_second_layer) LAYOUT_FN_9_12[slice_idx] else LAYOUT_FN_1_8[slice_idx],
            .Sys => LAYOUT_SYS[slice_idx],
            .Base => 0,
        };

        if (char_code == 0) return 0;

        var active_mods: i32 = 0;

        // Shift: Left Trigger (FLAG_SHIFT) held or Caps Lock toggled!
        const is_shift = ((state_flags & FLAG_SHIFT) != 0) != ((state_flags & FLAG_CAPS_LOCK) != 0);
        if (is_shift) {
            active_mods |= MOD_SHIFT;
            if (char_code >= 'a' and char_code <= 'z') {
                char_code -= 32;
            } else {
                char_code = shiftSymbol(char_code);
            }
        }

        // Ctrl: Left Stick Down
        if ((state_flags & FLAG_CTRL) != 0) {
            active_mods |= MOD_CTRL;
            if (char_code >= 'a' and char_code <= 'z') {
                char_code = char_code - 'a' + 1;
            } else if (char_code >= 'A' and char_code <= 'Z') {
                char_code = char_code - 'A' + 1;
            }
        }

        // Alt: Left Stick Left
        if ((state_flags & FLAG_ALT) != 0) {
            active_mods |= MOD_ALT;
        }

        // Super / Windows: Left Stick Right
        if ((state_flags & FLAG_SUPER) != 0) {
            active_mods |= MOD_SUPER;
        }

        return @as(i32, char_code) | active_mods;
    }
};

// ============================================================================
// GLOBAL STATIC ENGINE INSTANCE (Zero Heap Allocations)
// ============================================================================
var global_engine: InputStateMachine = .{};

// ============================================================================
// BARE-METAL JNI EXPORTS
// ============================================================================
pub export fn Java_com_radpad_app_InputEngine_processInput(
    env: ?*anyopaque,
    clazz: ?*anyopaque,
    x: f32,
    y: f32,
    button_mask: i32,
) callconv(.c) i32 {
    _ = env;
    _ = clazz;
    return global_engine.process(x, y, button_mask);
}

pub export fn Java_com_radpad_app_InputEngine_select(
    env: ?*anyopaque,
    clazz: ?*anyopaque,
    state_flags: i32,
) callconv(.c) i32 {
    _ = env;
    _ = clazz;
    return global_engine.handleSelection(state_flags);
}

pub export fn Java_com_radpad_app_InputEngine_backToBase(
    env: ?*anyopaque,
    clazz: ?*anyopaque,
) callconv(.c) void {
    _ = env;
    _ = clazz;
    global_engine.current_layer = .Base;
}

pub export fn Java_com_radpad_app_InputEngine_getCurrentLayer(
    env: ?*anyopaque,
    clazz: ?*anyopaque,
) callconv(.c) i32 {
    _ = env;
    _ = clazz;
    return @intFromEnum(global_engine.current_layer);
}

pub export fn Java_com_radpad_app_InputEngine_setCurrentLayer(
    env: ?*anyopaque,
    clazz: ?*anyopaque,
    layer_id: i32,
) callconv(.c) void {
    _ = env;
    _ = clazz;
    if (layer_id >= 0 and layer_id <= 8) {
        global_engine.current_layer = @enumFromInt(@as(u8, @intCast(layer_id)));
    }
}

pub export fn Java_com_radpad_app_InputEngine_resetState(
    env: ?*anyopaque,
    clazz: ?*anyopaque,
) callconv(.c) void {
    _ = env;
    _ = clazz;
    global_engine.reset();
}

pub export fn Java_com_radpad_app_InputEngine_setSymmetricSlices(
    env: ?*anyopaque,
    clazz: ?*anyopaque,
    symmetric: bool,
) callconv(.c) void {
    _ = env;
    _ = clazz;
    global_engine.use_symmetric_slices = symmetric;
}


// ============================================================================
// UNIT TESTS
// ============================================================================
test "Flick motion alone does not emit" {
    var engine = InputStateMachine{};
    // Move stick North past deadzone without R1
    const out = engine.process(0.0, -0.8, 0);
    try std.testing.expectEqual(@as(i32, 0), out);
    try std.testing.expect(engine.is_deflected);
    try std.testing.expectEqual(Direction.North, engine.last_aimed_slice);
}

test "Base layer selection with R1 enters A-H, then types a and c" {
    var engine = InputStateMachine{};

    // Aim North (A-H) and press R1
    const select_out = engine.process(0.0, -0.8, FLAG_SELECT);
    try std.testing.expectEqual(@as(i32, LAYER_EVENT_MASK | @intFromEnum(LayerId.A_H)), select_out);
    try std.testing.expectEqual(LayerId.A_H, engine.current_layer);

    // Aim North ('a') and press R1
    const out_a = engine.process(0.0, -0.8, FLAG_SELECT);
    try std.testing.expectEqual(@as(i32, 'a'), out_a & 0xFF);

    // Aim East ('c') and press R1
    const out_c = engine.process(0.8, 0.0, FLAG_SELECT);
    try std.testing.expectEqual(@as(i32, 'c'), out_c & 0xFF);
}

test "L1 returns from active layer to Base" {
    var engine = InputStateMachine{};
    engine.current_layer = .A_H;

    // Press L1 (FLAG_BACK)
    _ = engine.process(0.0, 0.0, FLAG_BACK);
    try std.testing.expectEqual(LayerId.Base, engine.current_layer);
}

test "MORE_SYM layer: sym 1 and sym 2 with R2 hold" {
    var engine = InputStateMachine{};
    engine.current_layer = .MoreSym;

    // North without R2 is '\''
    const out_quote = engine.process(0.0, -0.8, FLAG_SELECT);
    try std.testing.expectEqual(@as(i32, '\''), out_quote & 0xFF);

    // North with Shift is '"'
    const out_dquote = engine.process(0.0, -0.8, FLAG_SELECT | FLAG_SHIFT);
    try std.testing.expectEqual(@as(i32, '"'), out_dquote & 0xFF);

    // North with R2 (FLAG_R2_HOLD) is '`'
    const out_btick = engine.process(0.0, -0.8, FLAG_SELECT | FLAG_R2_HOLD);
    try std.testing.expectEqual(@as(i32, '`'), out_btick & 0xFF);

    // North with R2 + Shift is '~'
    const out_tilde = engine.process(0.0, -0.8, FLAG_SELECT | FLAG_R2_HOLD | FLAG_SHIFT);
    try std.testing.expectEqual(@as(i32, '~'), out_tilde & 0xFF);

    // East with R2 is ']'
    const out_rbr = engine.process(0.8, 0.0, FLAG_SELECT | FLAG_R2_HOLD);
    try std.testing.expectEqual(@as(i32, ']'), out_rbr & 0xFF);

    // West with R2 is '['
    const out_lbr = engine.process(-0.8, 0.0, FLAG_SELECT | FLAG_R2_HOLD);
    try std.testing.expectEqual(@as(i32, '['), out_lbr & 0xFF);
}

test "NUM_SYM layer: 1-8 and 9-0 with R2 hold" {
    var engine = InputStateMachine{};
    engine.current_layer = .NumSym;

    // North without R2 is '1'
    const out_1 = engine.process(0.0, -0.8, FLAG_SELECT);
    try std.testing.expectEqual(@as(i32, '1'), out_1 & 0xFF);

    // North with Shift is '!'
    const out_excl = engine.process(0.0, -0.8, FLAG_SELECT | FLAG_SHIFT);
    try std.testing.expectEqual(@as(i32, '!'), out_excl & 0xFF);

    // North with R2 is '9'
    const out_9 = engine.process(0.0, -0.8, FLAG_SELECT | FLAG_R2_HOLD);
    try std.testing.expectEqual(@as(i32, '9'), out_9 & 0xFF);

    // NorthEast with R2 is '0'
    const out_0 = engine.process(0.7, -0.7, FLAG_SELECT | FLAG_R2_HOLD);
    try std.testing.expectEqual(@as(i32, '0'), out_0 & 0xFF);
}

test "FN layer: F1-F8 and F9-F12 with R2 hold" {
    var engine = InputStateMachine{};
    engine.current_layer = .Fn;

    // North is F1
    const out_f1 = engine.process(0.0, -0.8, FLAG_SELECT);
    try std.testing.expectEqual(@as(i32, KEY_F1), out_f1 & 0xFFFF);

    // North with R2 is F9
    const out_f9 = engine.process(0.0, -0.8, FLAG_SELECT | FLAG_R2_HOLD);
    try std.testing.expectEqual(@as(i32, KEY_F9), out_f9 & 0xFFFF);

    // SouthEast with R2 is F12
    const out_f12 = engine.process(0.7, 0.7, FLAG_SELECT | FLAG_R2_HOLD);
    try std.testing.expectEqual(@as(i32, KEY_F12), out_f12 & 0xFFFF);
}

test "SYS layer: Vol Toggle in deadzone, PgUp, Vol+, Del around ring" {
    var engine = InputStateMachine{};
    engine.current_layer = .Sys;

    // Stick in deadzone (0, 0) + R1 -> KEY_VOL_MUTE
    const out_mute = engine.process(0.0, 0.0, FLAG_SELECT);
    try std.testing.expectEqual(@as(i32, KEY_VOL_MUTE), out_mute & 0xFFFF);

    // North is PgUp
    const out_pgup = engine.process(0.0, -0.8, FLAG_SELECT);
    try std.testing.expectEqual(@as(i32, KEY_PGUP), out_pgup & 0xFFFF);

    // NorthEast is Vol+
    const out_volup = engine.process(0.7, -0.7, FLAG_SELECT);
    try std.testing.expectEqual(@as(i32, KEY_VOL_UP), out_volup & 0xFFFF);

    // SouthEast is Delete
    const out_del = engine.process(0.7, 0.7, FLAG_SELECT);
    try std.testing.expectEqual(@as(i32, KEY_DELETE), out_del & 0xFFFF);

    // NorthWest is Vol-
    const out_voldown = engine.process(-0.7, -0.7, FLAG_SELECT);
    try std.testing.expectEqual(@as(i32, KEY_VOL_DOWN), out_voldown & 0xFFFF);
}

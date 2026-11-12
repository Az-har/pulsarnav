package com.pulsarnav.app.model

/**
 * Verified Maneuver Opcodes for the Bajaj / OTC Engineering digital cluster.
 * These correspond to the built-in icons on the Bajaj Pulsar NS200 / Dominar instrument consoles.
 */
enum class Maneuver(val opcode: Byte, val description: String) {
    TURN_LEFT(73, "Turn Left"),
    TURN_RIGHT(74, "Turn Right"),
    TURN_SLIGHT_LEFT(67, "Slight Left"),
    TURN_SLIGHT_RIGHT(68, "Slight Right"),
    TURN_SHARP_LEFT(69, "Sharp Left"),
    TURN_SHARP_RIGHT(70, "Sharp Right"),
    STRAIGHT(71, "Continue Straight"),
    DESTINATION_REACHED(72, "Destination Reached"),
    RAMP_LEFT(75, "Ramp Left"),
    RAMP_RIGHT(76, "Ramp Right"),
    ROUNDABOUT_RIGHT(78, "Roundabout Right"),
    U_TURN_LEFT(79, "U-Turn Left"),
    U_TURN_RIGHT(80, "U-Turn Right"),
    FORK_LEFT(81, "Fork Left"),
    FORK_RIGHT(82, "Fork Right"),
    FERRY_TRAIN(84, "Ferry Train"),
    ROUNDABOUT_LEFT(85, "Roundabout Left"),
    MERGE(86, "Merge"),
    KEEP_RIGHT(88, "Keep Right"),
    FERRY(89, "Ferry"),
    KEEP_LEFT(90, "Keep Left"),
    WRONG_WAY(66, "Wrong Way");
}

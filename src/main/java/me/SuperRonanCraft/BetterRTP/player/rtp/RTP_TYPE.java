package me.SuperRonanCraft.BetterRTP.player.rtp;

public enum RTP_TYPE {
    COMMAND, //Player executed command
    FORCED, //Player was forced to rtp (/rtp player <player>)
    RESPAWN, //Player respawned and world has RTPOnDeath enabled
    JOIN, //Player joined and was rtp'd automatically
    TEST, //Player was just testing out effects
}

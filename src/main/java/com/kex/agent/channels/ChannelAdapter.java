// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

public interface ChannelAdapter {
    String name();

    void send(ChannelMessage message);
}

package com.valerinsmp.vvotes.arclight;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.arclight.platform.ArclightAdapter;
import com.valerinsmp.vvotes.platform.PlatformAdapter;

public final class VVotesArclight extends VVotesPlugin {

    @Override
    protected PlatformAdapter createPlatformAdapter() {
        return new ArclightAdapter(this);
    }
}

package com.valerinsmp.vvotes.paper;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.paper.platform.PaperAdapter;
import com.valerinsmp.vvotes.platform.PlatformAdapter;

public final class VVotesPaper extends VVotesPlugin {

    @Override
    protected PlatformAdapter createPlatformAdapter() {
        return new PaperAdapter();
    }
}

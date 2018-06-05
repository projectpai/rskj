package org.ethereum.config.blockchain.mainnet;

public class MainNetFirstForkConfig extends MainNetAfterBridgeSyncConfig {
    @Override
    public boolean isRfs170() { return true; }
}

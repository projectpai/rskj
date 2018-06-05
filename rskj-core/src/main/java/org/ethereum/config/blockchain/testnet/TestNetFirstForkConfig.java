package org.ethereum.config.blockchain.testnet;

public class TestNetFirstForkConfig extends TestNetAfterBridgeSyncConfig {
    @Override
    public boolean isRfs170() { return true; }
}

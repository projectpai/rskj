/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.config;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.peg.AddressBasedAuthorizer;
import co.rsk.peg.Federation;
import com.google.common.collect.Lists;
import org.ethereum.crypto.ECKey;
import org.spongycastle.util.encoders.Hex;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class BridgeTestNetConstants extends BridgeConstants {
    private static BridgeTestNetConstants instance = new BridgeTestNetConstants();

    BridgeTestNetConstants() {
        btcParamsString = NetworkParameters.ID_TESTNET;

         BtcECKey federator0PublicKey = BtcECKey.fromPublicOnly(Hex.decode("03ade011a7d730a981f30e1d314d57d1a60e76739ec1f12582f70967054576ec15"));
         BtcECKey federator1PublicKey = BtcECKey.fromPublicOnly(Hex.decode("024991d7f49c94b000c516727d308721c471d0783ced0d11e6b217f48e079a26f8"));
         BtcECKey federator2PublicKey = BtcECKey.fromPublicOnly(Hex.decode("03c0382876de53001cba785de593ab61171d4e9670f33108d328e8bd26031fe145"));
         BtcECKey federator3PublicKey = BtcECKey.fromPublicOnly(Hex.decode("0228ccb924b660734634a67c9d68f05cadca949f8f3adddb4c6e33ce9d945dbadc"));
         BtcECKey federator4PublicKey = BtcECKey.fromPublicOnly(Hex.decode("024c749a7f6f98159fd35ba49b3d628f9b297c8f7dfbb045be9fff4010ab366cc1"));
         BtcECKey federator5PublicKey = BtcECKey.fromPublicOnly(Hex.decode("02d6284a04c1d0c2f50cb7c9fd599ad778eb850997ebcc672bd65a0ff2c2ff6ab2"));
         BtcECKey federator6PublicKey = BtcECKey.fromPublicOnly(Hex.decode("029c0f35b3507ec75ef264abc0d4728a334cc459273462c9b845774d8aba251173"));

        List<BtcECKey> genesisFederationPublicKeys = Lists.newArrayList(
                federator0PublicKey, federator1PublicKey, federator2PublicKey,
                federator3PublicKey, federator4PublicKey, federator5PublicKey,
                federator6PublicKey
        );

        // Currently set to:
        // Wednesday, January 3, 2018 12:00:00 AM GMT-03:00
        Instant genesisFederationAddressCreatedAt = Instant.ofEpochMilli(1514948400l);

        genesisFederation = new Federation(
                genesisFederationPublicKeys,
                genesisFederationAddressCreatedAt,
                1L,
                getBtcParams()
        );

        btc2RskMinimumAcceptableConfirmations = 10;
        btc2RskMinimumAcceptableConfirmationsOnRsk = 10;
        rsk2BtcMinimumAcceptableConfirmations = 10;

        updateBridgeExecutionPeriod = 3 * 60 * 1000; // 3 minutes

        maxBtcHeadersPerRskBlock = 500;

        minimumLockTxValue = Coin.valueOf(1000000);
        minimumReleaseTxValue = Coin.valueOf(500000);

        // Passphrases are kept private
        List<ECKey> federationChangeAuthorizedKeys = Arrays.stream(new String[]{
            "04948cfbe12df6fe502d03299d9d6d50858adb37f4c2bf2f66baad02f22de674748b16b7338e670f1e67552b4924837b282ee4183448e18af1b75b1ca79c8510ce",
            "044a3440ffe5cd02e2e57e15808dc4cf402912340d4592e83c7cb6717071b975e58eb76eb72f3568c27764b0c63fbc06a865c7d0ebdf490158acce311430a59d84",
            "042f406d5d438d6635ab349783089da883dc150258cf33461683baf4d291ce1c8b1f9a2efa34c5e9c6a52b4e41be5f9da59720a355a7c485d6f3282c6e02dba48b"
        }).map(hex -> ECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        federationChangeAuthorizer = new AddressBasedAuthorizer(
                federationChangeAuthorizedKeys,
                AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY
        );

        // Passphrases are kept private
        List<ECKey> lockWhitelistAuthorizedKeys = Arrays.stream(new String[]{
            "04fb61525707d63459ab110835977347e270b14e2976b21239dae8e7f4b285f829a6a504659b4c98f74fd53793302bedf2ac8a5a6a640d950fc45af0ab24f918ed"
        }).map(hex -> ECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        lockWhitelistChangeAuthorizer = new AddressBasedAuthorizer(
                lockWhitelistAuthorizedKeys,
                AddressBasedAuthorizer.MinimumRequiredCalculation.ONE
        );

        federationActivationAge = 60L;

        fundsMigrationAgeSinceActivationBegin = 60L;
        fundsMigrationAgeSinceActivationEnd = 900L;

        List<ECKey> feePerKbAuthorizedKeys = Arrays.stream(new String[]{
            "04522d38f7afe849b2f34763316c2d4d6b265b6552f25c4bbc892cba3b2851cffe4a4a43a2ea5f5ce2bbb5ce5f4e27ad33127cdf1f9f5dba9fc57798d19472f6af",
            "04d2216c572325a6063e424b589fdd9cf9a997e6ded52a22b47f680022ba1fabf559f5e6b4ab1a4575e730a998562376be850907a9d7aded07d428c7ac907678e8",
            "0422576758ce04ea376bc766c55cd57cf18e90c7cd96d37149c7bf956b6f7c510b14aeed3be18411ac9085129d5405830f59d26d5e63d7c9ead7980fd83966adea"
        }).map(hex -> ECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        feePerKbChangeAuthorizer = new AddressBasedAuthorizer(
                feePerKbAuthorizedKeys,
                AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY
        );

        genesisFeePerKb = Coin.MILLICOIN;
    }

    public static BridgeTestNetConstants getInstance() {
        return instance;
    }

}

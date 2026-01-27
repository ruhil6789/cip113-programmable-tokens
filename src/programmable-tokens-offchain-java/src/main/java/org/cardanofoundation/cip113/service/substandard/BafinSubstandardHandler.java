package org.cardanofoundation.cip113.service.substandard;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.function.exception.TxBuildException;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.easy1staking.cardano.util.UtxoUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.model.*;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.onchain.RegistryNode;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.service.ProtocolScriptBuilderService;
import org.cardanofoundation.cip113.service.SubstandardService;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Handler for the "bafin" programmable token substandard.
 * This substandard has 22 validators with complex parameterization
 * requirements.
 *
 * BASIC IMPLEMENTATION: Uses same registration logic as dummy for testing
 * purposes.
 * TODO: Implement full Bafin substandard logic with proper parameterization
 * when requirements are finalized.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BafinSubstandardHandler implements SubstandardHandler {

    private final ObjectMapper objectMapper;
    private final AppConfig.Network network;
    private final UtxoRepository utxoRepository;
    private final BFBackendService bfBackendService;
    private final RegistryNodeParser registryNodeParser;
    private final SubstandardService substandardService;
    private final ProtocolScriptBuilderService protocolScriptBuilderService;
    private final QuickTxBuilder quickTxBuilder;

    @Override
    public String getSubstandardId() {
        return "bafin";
    }

    @Override
    public RegisterTransactionContext buildRegistrationTransaction(
            RegisterTokenRequest registerTokenRequest,
            ProtocolBootstrapParams protocolBootstrapParams) {

        try {
            // Use same registration logic as dummy for now
            // Bafin validators have different script hashes, so they will produce different
            // policy IDs
            log.info("Building Bafin registration transaction (using basic implementation)");

            var directorySpendContract = protocolScriptBuilderService
                    .getParameterizedDirectorySpendScript(protocolBootstrapParams);

            // Use protocol params reference from bootstrap params (reference input)
            var protocolParamsTxInput = protocolBootstrapParams.protocolParams().txInput();
            String protocolParamsTxHash = protocolParamsTxInput.txHash();
            int protocolParamsOutputIndex = protocolParamsTxInput.outputIndex();
            log.info("Using protocol params reference input from bootstrap: {}:{}", protocolParamsTxHash,
                    protocolParamsOutputIndex);

            var directorySpendContractAddress = AddressProvider.getEntAddress(directorySpendContract,
                    network.getCardanoNetwork());
            log.info("directorySpendContractAddress: {}", directorySpendContractAddress.getAddress());

            var directoryMintContract = protocolScriptBuilderService
                    .getParameterizedDirectoryMintScript(protocolBootstrapParams);

            // Use issuance params reference from bootstrap params (reference input)
            var issuanceTxInput = protocolBootstrapParams.issuanceParams().txInput();
            String issuanceTxHash = issuanceTxInput.txHash();
            int issuanceOutputIndex = issuanceTxInput.outputIndex();
            log.info("Using issuance reference input from bootstrap: {}:{}", issuanceTxHash, issuanceOutputIndex);

            var rigistrarUtxosOpt = utxoRepository.findUnspentByOwnerAddr(registerTokenRequest.registrarAddress(),
                    Pageable.unpaged());
            if (rigistrarUtxosOpt.isEmpty()) {
                return RegisterTransactionContext.error("issuer wallet is empty");
            }
            var registrarUtxos = rigistrarUtxosOpt.get().stream().map(UtxoUtil::toUtxo).toList();

            var substandardIssuanceContractOpt = substandardService.getSubstandardValidator(
                    registerTokenRequest.substandardName(), registerTokenRequest.substandardIssueContractName());
            var substandardTransferContractOpt = substandardService.getSubstandardValidator(
                    registerTokenRequest.substandardName(), registerTokenRequest.substandardTransferContractName());

            // Handle third-party validator (can be empty for bafin)
            String thirdPartyScriptHash = "";
            if (registerTokenRequest.substandardThirdPartyContractName() != null &&
                    !registerTokenRequest.substandardThirdPartyContractName().isBlank()) {
                var thirdPartyContractOpt = substandardService.getSubstandardValidator(
                        registerTokenRequest.substandardName(),
                        registerTokenRequest.substandardThirdPartyContractName());
                if (thirdPartyContractOpt.isPresent()) {
                    thirdPartyScriptHash = thirdPartyContractOpt.get().scriptHash();
                }
            }

            if (substandardIssuanceContractOpt.isEmpty() || substandardTransferContractOpt.isEmpty()) {
                log.warn("substandard issuance or transfer contract are empty");
                return RegisterTransactionContext.error("substandard issuance or transfer contract are empty");
            }
            // Get parameterized validators using the handler methods
            // For Bafin validators, we need to parameterize them before use
            // If parameters are not available yet (e.g., during registration), 
            // we'll try to use unparameterized scripts as a fallback
            var substandardIssueContract = getParameterizedIssueValidator(
                    registerTokenRequest.substandardIssueContractName()
            );
            log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());

            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract,
                    network.getCardanoNetwork());
            log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());

            var substandardTransferContract = getParameterizedTransferValidator(
                    registerTokenRequest.substandardTransferContractName()
            );

            var issuanceContract = protocolScriptBuilderService
                    .getParameterizedIssuanceMintScript(protocolBootstrapParams, substandardIssueContract);
            final var progTokenPolicyId = issuanceContract.getPolicyId();
            log.info("issuanceContract: {}", progTokenPolicyId);

            // Fetch registry UTXOs from Blockfrost first, then fallback to repository
            log.info("Fetching registry UTXOs from address: {}", directorySpendContractAddress.getAddress());
            var registryUtxosResult = bfBackendService.getUtxoService()
                    .getUtxos(directorySpendContractAddress.getAddress(), 100, 1);
            List<Utxo> blockfrostRegistryUtxos = List.of();

            if (registryUtxosResult.isSuccessful() && registryUtxosResult.getValue() != null) {
                blockfrostRegistryUtxos = registryUtxosResult.getValue();
                log.info("Successfully fetched {} registry UTXOs from Blockfrost", blockfrostRegistryUtxos.size());
            } else {
                log.warn(
                        "Blockfrost fetch failed for registry UTXOs (successful: {}, value: {}), falling back to repository",
                        registryUtxosResult.isSuccessful(), registryUtxosResult.getValue() != null);
            }

            // Also get from repository as fallback
            var registryEntries = utxoRepository
                    .findUnspentByOwnerPaymentCredential(directorySpendContract.getPolicyId(), Pageable.unpaged());
            var repositoryRegistryUtxos = registryEntries.stream()
                    .flatMap(Collection::stream)
                    .map(UtxoUtil::toUtxo)
                    .toList();

            log.info("Found {} registry UTXOs from repository", repositoryRegistryUtxos.size());

            // Combine both sources, preferring Blockfrost
            var allRegistryUtxos = Stream.concat(blockfrostRegistryUtxos.stream(), repositoryRegistryUtxos.stream())
                    .distinct() // Remove duplicates based on txHash:outputIndex
                    .toList();

            log.info("Total registry UTXOs available: {}", allRegistryUtxos.size());

            // Check if token is already registered
            var registryEntryOpt = allRegistryUtxos.stream()
                    .filter(utxo -> {
                        if (utxo.getInlineDatum() == null)
                            return false;
                        return registryNodeParser.parse(utxo.getInlineDatum())
                                .map(registryNode -> registryNode.key().equals(progTokenPolicyId))
                                .orElse(false);
                    })
                    .findAny();

            if (registryEntryOpt.isEmpty()) {

                // Find the node to replace (where new token should be inserted)
                var nodeToReplaceOpt = allRegistryUtxos.stream()
                        .filter(utxo -> {
                            if (utxo.getInlineDatum() == null) {
                                log.warn("Registry UTXO has no inline datum: {}:{}", utxo.getTxHash(),
                                        utxo.getOutputIndex());
                                return false;
                            }

                            var registryDatumOpt = registryNodeParser.parse(utxo.getInlineDatum());

                            if (registryDatumOpt.isEmpty()) {
                                log.warn("could not parse registry datum for: {}", utxo.getInlineDatum());
                                return false;
                            }

                            var registryDatum = registryDatumOpt.get();

                            var after = registryDatum.key().compareTo(progTokenPolicyId) < 0;
                            var before = progTokenPolicyId.compareTo(registryDatum.next()) < 0;
                            log.info("Checking registry node: key={}, next={}, after={}, before={}",
                                    registryDatum.key(), registryDatum.next(), after, before);
                            return after && before;

                        })
                        .findAny();

                // Check if registry is empty - need to use DirectoryInit
                boolean isRegistryEmpty = allRegistryUtxos.isEmpty();

                RegistryNode directoryMintDatum;
                RegistryNode directorySpendDatum;
                Asset directoryMintNft;
                Asset directorySpendNft;
                ConstrPlutusData directoryMintRedeemer;
                Utxo directoryUtxo = null;

                if (isRegistryEmpty) {
                    // DirectoryInit: Create sentinel node only
                    log.info("Registry is empty - using DirectoryInit to create sentinel node");

                    // DirectoryInit redeemer is constr(0)
                    directoryMintRedeemer = ConstrPlutusData.of(0);

                    // Sentinel node NFT (empty token name "0x")
                    directoryMintNft = Asset.builder()
                            .name("0x")
                            .value(BigInteger.ONE)
                            .build();

                    // Sentinel node datum: key="", next=max_value, empty credentials
                    directoryMintDatum = new RegistryNode("",
                            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", // max value for next
                            "",
                            "",
                            "");

                    // For DirectoryInit, we don't have a directorySpend (no existing node to
                    // update)
                    directorySpendDatum = null;
                    directorySpendNft = null;

                    // For DirectoryInit, we need to spend the directoryMintParams.txInput (the
                    // bootstrap UTXO)
                    var directoryMintTxInput = protocolBootstrapParams.directoryMintParams().txInput();
                    log.info("DirectoryInit: fetching bootstrap UTXO {}:{}", directoryMintTxInput.txHash(),
                            directoryMintTxInput.outputIndex());
                    var directoryMintUtxoResult = bfBackendService.getUtxoService()
                            .getTxOutput(directoryMintTxInput.txHash(), directoryMintTxInput.outputIndex());
                    if (!directoryMintUtxoResult.isSuccessful() || directoryMintUtxoResult.getValue() == null) {
                        String errorDetails = "Unknown error";
                        if (directoryMintUtxoResult.getResponse() != null) {
                            errorDetails = directoryMintUtxoResult.getResponse().toString();
                        }
                        log.error("Failed to fetch bootstrap UTXO for DirectoryInit: {}:{}. Error: {}",
                                directoryMintTxInput.txHash(), directoryMintTxInput.outputIndex(), errorDetails);

                        var txResult = bfBackendService.getTransactionService()
                                .getTransaction(directoryMintTxInput.txHash());
                        if (txResult.isSuccessful() && txResult.getValue() != null) {
                            log.info("Transaction {} exists, but UTXO {}:{} is not available. It may have been spent.",
                                    directoryMintTxInput.txHash(), directoryMintTxInput.txHash(),
                                    directoryMintTxInput.outputIndex());
                            return RegisterTransactionContext
                                    .error("Bootstrap UTXO " + directoryMintTxInput.txHash() + ":" +
                                            directoryMintTxInput.outputIndex()
                                            + " has been spent. DirectoryInit can only be performed once.");
                        } else {
                            return RegisterTransactionContext.error("Could not fetch bootstrap UTXO for DirectoryInit: "
                                    +
                                    directoryMintTxInput.txHash() + ":" + directoryMintTxInput.outputIndex() +
                                    ". Transaction may not exist or Blockfrost may not have indexed it yet. Error: "
                                    + errorDetails);
                        }
                    }
                    directoryUtxo = directoryMintUtxoResult.getValue();
                    log.info("DirectoryInit: fetched bootstrap UTXO successfully: {}:{}", directoryUtxo.getTxHash(),
                            directoryUtxo.getOutputIndex());

                } else {
                    // DirectoryInsert: Insert into existing registry
                    if (nodeToReplaceOpt.isEmpty()) {
                        log.error("Could not find node to replace. Total registry UTXOs: {}", allRegistryUtxos.size());
                        return RegisterTransactionContext.error("could not find node to replace");
                    }

                    directoryUtxo = nodeToReplaceOpt.get();
                    log.info("directoryUtxo: {}:{}", directoryUtxo.getTxHash(), directoryUtxo.getOutputIndex());
                    var existingRegistryNodeDatumOpt = registryNodeParser.parse(directoryUtxo.getInlineDatum());

                    if (existingRegistryNodeDatumOpt.isEmpty()) {
                        return RegisterTransactionContext.error("could not parse current registry node");
                    }

                    var existingRegistryNodeDatum = existingRegistryNodeDatumOpt.get();

                    // Directory MINT - NFT, address, datum and value
                    directoryMintRedeemer = ConstrPlutusData.of(1,
                            BytesPlutusData.of(issuanceContract.getScriptHash()),
                            BytesPlutusData.of(substandardIssueContract.getScriptHash()));

                    directoryMintNft = Asset.builder()
                            .name("0x" + issuanceContract.getPolicyId())
                            .value(BigInteger.ONE)
                            .build();

                    directorySpendNft = Asset.builder()
                            .name("0x")
                            .value(BigInteger.ONE)
                            .build();

                    directorySpendDatum = existingRegistryNodeDatum.toBuilder()
                            .next(HexUtil.encodeHexString(issuanceContract.getScriptHash()))
                            .build();
                    log.info("directorySpendDatum: {}", directorySpendDatum);

                    directoryMintDatum = new RegistryNode(HexUtil.encodeHexString(issuanceContract.getScriptHash()),
                            existingRegistryNodeDatum.next(),
                            HexUtil.encodeHexString(substandardTransferContract.getScriptHash()),
                            thirdPartyScriptHash,
                            "");
                    log.info("directoryMintDatum: {}", directoryMintDatum);
                }

                Value directoryMintValue = Value.builder()
                        .coin(Amount.ada(1).getQuantity())
                        .multiAssets(List.of(
                                MultiAsset.builder()
                                        .policyId(directoryMintContract.getPolicyId())
                                        .assets(List.of(directoryMintNft))
                                        .build()))
                        .build();
                log.info("directoryMintValue: {}", directoryMintValue);

                // DirectorySpendValue is only needed for DirectoryInsert (when updating
                // existing node)
                Value directorySpendValue = null;
                if (!isRegistryEmpty && directorySpendNft != null) {
                    directorySpendValue = Value.builder()
                            .coin(Amount.ada(1).getQuantity())
                            .multiAssets(List.of(
                                    MultiAsset.builder()
                                            .policyId(directoryMintContract.getPolicyId())
                                            .assets(List.of(directorySpendNft))
                                            .build()))
                            .build();
                    log.info("directorySpendValue: {}", directorySpendValue);
                }

                var issuanceRedeemer = ConstrPlutusData.of(0,
                        ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())));

                // Programmable Token Mint
                var programmableToken = Asset.builder()
                        .name("0x" + registerTokenRequest.assetName())
                        .value(new BigInteger(registerTokenRequest.quantity()))
                        .build();

                Value programmableTokenValue = Value.builder()
                        .coin(Amount.ada(1).getQuantity())
                        .multiAssets(List.of(
                                MultiAsset.builder()
                                        .policyId(issuanceContract.getPolicyId())
                                        .assets(List.of(programmableToken))
                                        .build()))
                        .build();

                var payee = registerTokenRequest.recipientAddress() == null
                        || registerTokenRequest.recipientAddress().isBlank() ? registerTokenRequest.registrarAddress()
                                : registerTokenRequest.recipientAddress();
                log.info("payee: {}", payee);

                var payeeAddress = new Address(payee);

                var targetAddress = AddressProvider.getBaseAddress(
                        Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                        payeeAddress.getDelegationCredential().get(),
                        network.getCardanoNetwork());

                var tx = new ScriptTx()
                        .collectFrom(registrarUtxos);

                // For DirectoryInit, we spend the bootstrap UTXO; for DirectoryInsert, we
                // collect the directory UTXO
                if (isRegistryEmpty) {
                    // DirectoryInit: spend the bootstrap UTXO (directoryMintParams.txInput)
                    tx.collectFrom(directoryUtxo, ConstrPlutusData.of(0));
                    log.info("DirectoryInit: collecting bootstrap UTXO {}:{}", directoryUtxo.getTxHash(),
                            directoryUtxo.getOutputIndex());
                } else {
                    // DirectoryInsert: collect the existing directory UTXO
                    tx.collectFrom(directoryUtxo, ConstrPlutusData.of(0));
                    log.info("DirectoryInsert: collecting directory UTXO {}:{}", directoryUtxo.getTxHash(),
                            directoryUtxo.getOutputIndex());
                }

                tx.withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(100))
                        // Mint Token
                        .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                        // Directory mint redeemer: DirectoryInit (constr(0)) or DirectoryInsert
                        // (constr(1))
                        .mintAsset(directoryMintContract, directoryMintNft, directoryMintRedeemer)
                        .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue),
                                ConstrPlutusData.of(0));

                // For DirectoryInit, we only create the sentinel node (no directorySpend)
                // For DirectoryInsert, we update existing node and create new node
                if (isRegistryEmpty) {
                    // DirectoryInit: create sentinel node only (no existing node to update)
                    tx.payToContract(directorySpendContractAddress.getAddress(),
                            ValueUtil.toAmountList(directoryMintValue), directoryMintDatum.toPlutusData());
                } else {
                    // DirectoryInsert: update existing node (spend) and create new node (mint)
                    tx.payToContract(directorySpendContractAddress.getAddress(),
                            ValueUtil.toAmountList(directorySpendValue), directorySpendDatum.toPlutusData())
                            .payToContract(directorySpendContractAddress.getAddress(),
                                    ValueUtil.toAmountList(directoryMintValue), directoryMintDatum.toPlutusData());
                }

                tx.readFrom(TransactionInput.builder()
                        .transactionId(protocolParamsTxHash)
                        .index(protocolParamsOutputIndex)
                        .build(),
                        TransactionInput.builder()
                                .transactionId(issuanceTxHash)
                                .index(issuanceOutputIndex)
                                .build())
                        .attachSpendingValidator(directorySpendContract)
                        .attachRewardValidator(substandardIssueContract)
                        .withChangeAddress(registerTokenRequest.registrarAddress());

                var transaction = quickTxBuilder.compose(tx)
                        .feePayer(registerTokenRequest.registrarAddress())
                        .mergeOutputs(false) // <-- this is important! or directory tokens will go to same address
                        .preBalanceTx((txBuilderContext, transaction1) -> {
                            var outputs = transaction1.getBody().getOutputs();
                            if (outputs.getFirst().getAddress().equals(registerTokenRequest.registrarAddress())) {
                                log.info("found dummy input, moving it...");
                                var first = outputs.removeFirst();
                                outputs.addLast(first);
                            }
                            try {
                                log.info("pre tx: {}", objectMapper.writeValueAsString(transaction1));
                            } catch (JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .postBalanceTx((txBuilderContext, transaction1) -> {
                            try {
                                log.info("post tx: {}", objectMapper.writeValueAsString(transaction1));
                            } catch (JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .build();

                log.info("tx: {}", transaction.serializeToHex());
                log.info("tx: {}", objectMapper.writeValueAsString(transaction));

                return RegisterTransactionContext.ok(transaction.serializeToHex(), progTokenPolicyId);
            } else {
                return RegisterTransactionContext
                        .error(String.format("Token policy %s already registered", progTokenPolicyId));
            }

        } catch (TxBuildException e) {
            // Handle script cost evaluation failures specifically
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("Failed to compute script cost") || 
                                     errorMsg.contains("DeserialiseFailure") ||
                                     errorMsg.contains("CBOR"))) {
                log.warn(
                        "Script cost evaluation failed for Bafin transaction. This may be due to validator parameterization. Error: {}",
                        errorMsg);
                
                // Try to provide helpful guidance
                String guidance = "Bafin validators require proper parameterization. ";
                if (errorMsg.contains("DeserialiseFailure") || errorMsg.contains("CBOR")) {
                    guidance += "The validators have been parameterized with empty/default parameters, but they may need actual values. ";
                    guidance += "Please ensure the validators are properly compiled and that the correct parameters are provided.";
                } else {
                    guidance += "Please check that the validators are properly compiled and available.";
                }
                
                return RegisterTransactionContext.error(
                        "Failed to compute script cost for Bafin validators. " + guidance + 
                        " Original error: " + errorMsg);
            }
            log.error("TxBuildException building Bafin registration transaction", e);
            return RegisterTransactionContext.error("Transaction build failed: " + errorMsg);
        } catch (Exception e) {
            log.error("Error building Bafin registration transaction", e);
            return RegisterTransactionContext.error(e.getMessage());
        }
    }

    @Override
    public TransactionContext buildMintTransaction(
            MintTokenRequest request,
            ProtocolBootstrapParams protocolParams) {
        // TODO: Implement Bafin minting logic
        log.warn("Bafin minting not yet implemented");
        throw new UnsupportedOperationException("Bafin substandard minting not yet implemented");
    }

    @Override
    public TransactionContext buildTransferTransaction(
            TransferTokenRequest request,
            ProtocolBootstrapParams protocolParams) {
        // TODO: Implement Bafin transfer logic
        log.warn("Bafin transfer not yet implemented");
        throw new UnsupportedOperationException("Bafin substandard transfer not yet implemented");
    }

    @Override
    public Set<String> getRequiredValidators() {
        // Bafin has 22 validators total
        // TODO: Define complete list of required validator names
        return Set.of(
                "issue_validator",
                "transfer_validator",
                // Plus 20 additional third-party validators
                "validator_3", "validator_4", "validator_5", "validator_6",
                "validator_7", "validator_8", "validator_9", "validator_10",
                "validator_11", "validator_12", "validator_13", "validator_14",
                "validator_15", "validator_16", "validator_17", "validator_18",
                "validator_19", "validator_20", "validator_21", "validator_22");
    }

    @Override
    public PlutusScript getParameterizedIssueValidator(String contractName, Object... params) {
        var validatorOpt = substandardService.getSubstandardValidator(getSubstandardId(), contractName);
        if (validatorOpt.isEmpty()) {
            throw new IllegalArgumentException("Validator not found: " + contractName);
        }

        var validator = validatorOpt.get();
        
        // Check if parameters are provided
        if (params != null && params.length > 0) {
            try {
                // Build parameter list based on provided parameters
                // Bafin validators typically need parameters like:
                // - security_asset_name (ByteArray)
                // - config_policy_id (ByteArray)  
                // - global_state_policy_id (ByteArray)
                // - power_users_linked_list_policy_id (ByteArray)
                // - issuance_policy_id (PolicyId)
                
                ListPlutusData paramList;
                if (params.length == 1 && params[0] instanceof ListPlutusData) {
                    // If a ListPlutusData is directly provided, use it
                    paramList = (ListPlutusData) params[0];
                } else {
                    // Convert individual parameters to PlutusData
                    var plutusDataList = new java.util.ArrayList<PlutusData>();
                    for (Object param : params) {
                        if (param instanceof String) {
                            plutusDataList.add(BytesPlutusData.of(HexUtil.decodeHexString((String) param)));
                        } else if (param instanceof byte[]) {
                            plutusDataList.add(BytesPlutusData.of((byte[]) param));
                        } else if (param instanceof Integer) {
                            plutusDataList.add(BigIntPlutusData.of((Integer) param));
                        } else if (param instanceof BigInteger) {
                            plutusDataList.add(BigIntPlutusData.of((BigInteger) param));
                        } else if (param instanceof PlutusData) {
                            plutusDataList.add((PlutusData) param);
                        } else {
                            log.warn("Unknown parameter type for Bafin validator: {}", param.getClass());
                            plutusDataList.add(BytesPlutusData.of(new byte[0])); // Placeholder
                        }
                    }
                    paramList = ListPlutusData.of(plutusDataList.toArray(new PlutusData[0]));
                }
                
                // Apply parameters to script
                var parameterizedBytes = AikenScriptUtil.applyParamToScript(paramList, validator.scriptBytes());
                return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(parameterizedBytes, PlutusVersion.v3);
            } catch (Exception e) {
                log.warn("Failed to parameterize Bafin issue validator '{}' with provided parameters, trying unparameterized: {}", 
                        contractName, e.getMessage());
            }
        }
        
        // Fallback: provide empty parameters to allow script to be parameterized
        // Bafin validators require parameters, so we provide empty/default ones
        log.warn("No parameters provided for Bafin issue validator '{}'. Using empty parameters as fallback.", contractName);
        
        // Try different parameter counts (common Bafin validators need 2-4 parameters)
        int[] paramCounts = {4, 3, 2, 1};
        Exception lastException = null;
        
        for (int paramCount : paramCounts) {
            try {
                // Create empty parameters based on count
                var emptyParamsList = new java.util.ArrayList<PlutusData>();
                for (int i = 0; i < paramCount; i++) {
                    emptyParamsList.add(BytesPlutusData.of(new byte[0]));
                }
                var emptyParams = ListPlutusData.of(emptyParamsList.toArray(new PlutusData[0]));
                
                var parameterizedBytes = AikenScriptUtil.applyParamToScript(emptyParams, validator.scriptBytes());
                var script = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(parameterizedBytes, PlutusVersion.v3);
                log.info("Successfully parameterized Bafin issue validator '{}' with {} empty parameters", contractName, paramCount);
                return script;
            } catch (Exception e) {
                lastException = e;
                log.debug("Failed to parameterize Bafin issue validator '{}' with {} parameters: {}", contractName, paramCount, e.getMessage());
                // Try next parameter count
            }
        }
        
        // If all parameter counts failed, try unparameterized as last resort
        log.error("Failed to parameterize Bafin issue validator '{}' with any parameter count. Last error: {}", contractName, lastException != null ? lastException.getMessage() : "unknown");
        try {
            return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    validator.scriptBytes(),
                    PlutusVersion.v3);
        } catch (Exception e2) {
            throw new IllegalArgumentException("Bafin validator '" + contractName + 
                    "' requires parameters but none were provided. All parameterization attempts failed. " +
                    "Last parameterization error: " + (lastException != null ? lastException.getMessage() : "unknown") +
                    ", Unparameterized error: " + e2.getMessage(), lastException != null ? lastException : e2);
        }
    }

    @Override
    public PlutusScript getParameterizedTransferValidator(String contractName, Object... params) {
        var validatorOpt = substandardService.getSubstandardValidator(getSubstandardId(), contractName);
        if (validatorOpt.isEmpty()) {
            throw new IllegalArgumentException("Validator not found: " + contractName);
        }

        var validator = validatorOpt.get();
        
        // Check if parameters are provided
        if (params != null && params.length > 0) {
            try {
                // Build parameter list based on provided parameters
                // Bafin transfer validators typically need parameters like:
                // - security_asset_name (ByteArray)
                // - global_state_policy_id (ByteArray)
                // - issuance_policy_id (PolicyId)
                
                ListPlutusData paramList;
                if (params.length == 1 && params[0] instanceof ListPlutusData) {
                    // If a ListPlutusData is directly provided, use it
                    paramList = (ListPlutusData) params[0];
                } else {
                    // Convert individual parameters to PlutusData
                    var plutusDataList = new java.util.ArrayList<PlutusData>();
                    for (Object param : params) {
                        if (param instanceof String) {
                            plutusDataList.add(BytesPlutusData.of(HexUtil.decodeHexString((String) param)));
                        } else if (param instanceof byte[]) {
                            plutusDataList.add(BytesPlutusData.of((byte[]) param));
                        } else if (param instanceof Integer) {
                            plutusDataList.add(BigIntPlutusData.of((Integer) param));
                        } else if (param instanceof BigInteger) {
                            plutusDataList.add(BigIntPlutusData.of((BigInteger) param));
                        } else if (param instanceof PlutusData) {
                            plutusDataList.add((PlutusData) param);
                        } else {
                            log.warn("Unknown parameter type for Bafin validator: {}", param.getClass());
                            plutusDataList.add(BytesPlutusData.of(new byte[0])); // Placeholder
                        }
                    }
                    paramList = ListPlutusData.of(plutusDataList.toArray(new PlutusData[0]));
                }
                
                // Apply parameters to script
                var parameterizedBytes = AikenScriptUtil.applyParamToScript(paramList, validator.scriptBytes());
                return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(parameterizedBytes, PlutusVersion.v3);
            } catch (Exception e) {
                log.warn("Failed to parameterize Bafin transfer validator '{}' with provided parameters, trying unparameterized: {}", 
                        contractName, e.getMessage());
            }
        }
        
        // Fallback: provide empty parameters to allow script to be parameterized
        // Bafin validators require parameters, so we provide empty/default ones
        log.warn("No parameters provided for Bafin transfer validator '{}'. Using empty parameters as fallback.", contractName);
        
        // Try different parameter counts (common Bafin transfer validators need 2-4 parameters)
        int[] paramCounts = {4, 3, 2, 1};
        Exception lastException = null;
        
        for (int paramCount : paramCounts) {
            try {
                // Create empty parameters based on count
                var emptyParamsList = new java.util.ArrayList<PlutusData>();
                for (int i = 0; i < paramCount; i++) {
                    emptyParamsList.add(BytesPlutusData.of(new byte[0]));
                }
                var emptyParams = ListPlutusData.of(emptyParamsList.toArray(new PlutusData[0]));
                
                var parameterizedBytes = AikenScriptUtil.applyParamToScript(emptyParams, validator.scriptBytes());
                var script = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(parameterizedBytes, PlutusVersion.v3);
                log.info("Successfully parameterized Bafin transfer validator '{}' with {} empty parameters", contractName, paramCount);
                return script;
            } catch (Exception e) {
                lastException = e;
                log.debug("Failed to parameterize Bafin transfer validator '{}' with {} parameters: {}", contractName, paramCount, e.getMessage());
                // Try next parameter count
            }
        }
        
        // If all parameter counts failed, try unparameterized as last resort
        log.error("Failed to parameterize Bafin transfer validator '{}' with any parameter count. Last error: {}", contractName, lastException != null ? lastException.getMessage() : "unknown");
        try {
            return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    validator.scriptBytes(),
                    PlutusVersion.v3);
        } catch (Exception e2) {
            throw new IllegalArgumentException("Bafin validator '" + contractName + 
                    "' requires parameters but none were provided. All parameterization attempts failed. " +
                    "Last parameterization error: " + (lastException != null ? lastException.getMessage() : "unknown") +
                    ", Unparameterized error: " + e2.getMessage(), lastException != null ? lastException : e2);
        }
    }

    @Override
    public PlutusScript getParameterizedThirdPartyValidator(String contractName, Object... params) {
        var validatorOpt = substandardService.getSubstandardValidator(getSubstandardId(), contractName);
        if (validatorOpt.isEmpty()) {
            throw new IllegalArgumentException("Validator not found: " + contractName);
        }

        var validator = validatorOpt.get();
        
        // Check if parameters are provided
        if (params != null && params.length > 0) {
            try {
                // Build parameter list based on provided parameters
                ListPlutusData paramList;
                if (params.length == 1 && params[0] instanceof ListPlutusData) {
                    // If a ListPlutusData is directly provided, use it
                    paramList = (ListPlutusData) params[0];
                } else {
                    // Convert individual parameters to PlutusData
                    var plutusDataList = new java.util.ArrayList<PlutusData>();
                    for (Object param : params) {
                        if (param instanceof String) {
                            plutusDataList.add(BytesPlutusData.of(HexUtil.decodeHexString((String) param)));
                        } else if (param instanceof byte[]) {
                            plutusDataList.add(BytesPlutusData.of((byte[]) param));
                        } else if (param instanceof Integer) {
                            plutusDataList.add(BigIntPlutusData.of((Integer) param));
                        } else if (param instanceof BigInteger) {
                            plutusDataList.add(BigIntPlutusData.of((BigInteger) param));
                        } else if (param instanceof PlutusData) {
                            plutusDataList.add((PlutusData) param);
                        } else {
                            log.warn("Unknown parameter type for Bafin validator: {}", param.getClass());
                            plutusDataList.add(BytesPlutusData.of(new byte[0])); // Placeholder
                        }
                    }
                    paramList = ListPlutusData.of(plutusDataList.toArray(new PlutusData[0]));
                }
                
                // Apply parameters to script
                var parameterizedBytes = AikenScriptUtil.applyParamToScript(paramList, validator.scriptBytes());
                return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(parameterizedBytes, PlutusVersion.v3);
            } catch (Exception e) {
                log.warn("Failed to parameterize Bafin third-party validator '{}' with provided parameters, trying unparameterized: {}", 
                        contractName, e.getMessage());
            }
        }
        
        // Fallback: provide empty parameters to allow script to be parameterized
        // Bafin validators require parameters, so we provide empty/default ones
        log.warn("No parameters provided for Bafin third-party validator '{}'. Using empty parameters as fallback.", contractName);
        
        // Try different parameter counts (third-party validators vary, typically 2-4 parameters)
        int[] paramCounts = {4, 3, 2, 1};
        Exception lastException = null;
        
        for (int paramCount : paramCounts) {
            try {
                // Create empty parameters based on count
                var emptyParamsList = new java.util.ArrayList<PlutusData>();
                for (int i = 0; i < paramCount; i++) {
                    emptyParamsList.add(BytesPlutusData.of(new byte[0]));
                }
                var emptyParams = ListPlutusData.of(emptyParamsList.toArray(new PlutusData[0]));
                
                var parameterizedBytes = AikenScriptUtil.applyParamToScript(emptyParams, validator.scriptBytes());
                var script = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(parameterizedBytes, PlutusVersion.v3);
                log.info("Successfully parameterized Bafin third-party validator '{}' with {} empty parameters", contractName, paramCount);
                return script;
            } catch (Exception e) {
                lastException = e;
                log.debug("Failed to parameterize Bafin third-party validator '{}' with {} parameters: {}", contractName, paramCount, e.getMessage());
                // Try next parameter count
            }
        }
        
        // If all parameter counts failed, try unparameterized as last resort
        log.error("Failed to parameterize Bafin third-party validator '{}' with any parameter count. Last error: {}", contractName, lastException != null ? lastException.getMessage() : "unknown");
        try {
            return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    validator.scriptBytes(),
                    PlutusVersion.v3);
        } catch (Exception e2) {
            throw new IllegalArgumentException("Bafin validator '" + contractName + 
                    "' requires parameters but none were provided. All parameterization attempts failed. " +
                    "Last parameterization error: " + (lastException != null ? lastException.getMessage() : "unknown") +
                    ", Unparameterized error: " + e2.getMessage(), lastException != null ? lastException : e2);
        }
    }
}

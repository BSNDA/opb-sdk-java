package irita.sdk.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageV3;
import irita.sdk.config.ClientConfig;
import irita.sdk.config.OpbConfig;
import irita.sdk.constant.TxStatus;
import irita.sdk.constant.enums.BroadcastMode;
import irita.sdk.constant.enums.MsgEnum;
import irita.sdk.exception.IritaSDKException;
import irita.sdk.model.*;
import irita.sdk.model.block.BlockResult;
import irita.sdk.model.block.ResultBlock;
import irita.sdk.model.block.ResultBlockResults;
import irita.sdk.model.block.ResultBlockRpc;
import irita.sdk.model.tx.Body;
import irita.sdk.model.tx.EventQueryBuilder;
import irita.sdk.model.tx.TxRpc;
import irita.sdk.model.tx.TxsRpc;
import irita.sdk.util.HashUtils;
import irita.sdk.util.HttpUtils;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.util.encoders.Hex;
import proto.cosmos.tx.v1beta1.TxOuterClass;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class RpcClient {
    private final String rpcUri;
    private final HttpUtils httpUtils;

    public RpcClient(String rpcUri) {
        this.rpcUri = rpcUri;
        this.httpUtils = new HttpUtils();
    }

    public RpcClient(ClientConfig clientConfig, OpbConfig opbConfig) {
        this.rpcUri = clientConfig.getRpcUri();

        // need set projectKey
        if (opbConfig != null && StringUtils.isNotEmpty(opbConfig.getProjectKey())) {
            this.httpUtils = new HttpUtils(opbConfig.getProjectKey());
        } else {
            this.httpUtils = new HttpUtils();
        }
    }

    public synchronized ResultTx broadcastTx(byte[] txBytes, BroadcastMode mode) throws IOException {
        if (mode == null) {
            throw new IritaSDKException("unknown broadcastMode");
        }
        switch (mode) {
            case Sync:
                return broadcastTxSync(txBytes);
            case Async:
                return broadcastTxAsync(txBytes);
            case Commit:
                return broadcastTxCommit(txBytes);
            default:
                throw new IritaSDKException("unknown broadcastMode");
        }
    }

    public synchronized ResultTx broadcastTxCommit(byte[] txBytes) throws IOException {
        JsonRpc jsonRpc = JsonRpc.WrapTxBytes(txBytes, "broadcast_tx_commit");
        ObjectMapper mapper = new ObjectMapper();
        String str = httpUtils.post(rpcUri, mapper.writeValueAsString(jsonRpc));
        return checkResTxAndConvert(str);
    }

    public synchronized ResultTx broadcastTxAsync(byte[] txBytes) throws IOException {
        JsonRpc jsonRpc = JsonRpc.WrapTxBytes(txBytes, "broadcast_tx_async");
        ObjectMapper mapper = new ObjectMapper();
        String str = httpUtils.post(rpcUri, mapper.writeValueAsString(jsonRpc));
        return mapper.readValue(str, ResultTx.class);
    }


    public synchronized ResultTx broadcastTxSync(byte[] txBytes) throws IOException {
        JsonRpc jsonRpc = JsonRpc.WrapTxBytes(txBytes, "broadcast_tx_sync");
        ObjectMapper mapper = new ObjectMapper();
        String str = httpUtils.post(rpcUri, mapper.writeValueAsString(jsonRpc));
        return mapper.readValue(str, ResultTx.class);
    }

    public synchronized GasInfo simulateTx(byte[] txBytes) throws IOException {
        JsonRpc jsonRpc = JsonRpc.WrapAbciQuery(txBytes, "app/simulate");
        ObjectMapper mapper = new ObjectMapper();
        String str = httpUtils.post(rpcUri, mapper.writeValueAsString(jsonRpc));
        JsonRpcQueryResponse resp = mapper.readValue(str, JsonRpcQueryResponse.class);
        Objects.requireNonNull(resp, "use json deserialize json_rpc_response return null");

        String value = Optional.of(resp)
                .map(JsonRpcQueryResponse::getResult)
                .map(ResultABCIQuery::getResponse)
                .map(ResponseQuery::getValue)
                .map(x -> new String(Base64.getDecoder().decode(x)))
                .orElse("");
        if (StringUtils.isEmpty(value)) {
            throw new IritaSDKException(resp.getResult().getResponse().getLog());
        }

        GasInfoWrap gasInfoWrap = mapper.readValue(value, GasInfoWrap.class);
        return gasInfoWrap.getGasInfo();
    }

    private ResultTx checkResTxAndConvert(String res) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ResultTx resultTx = mapper.readValue(res, ResultTx.class);

        if (resultTx.getError() != null) {
            throw new IritaSDKException(resultTx.getError().getData());
        }
        if (resultTx.getCode() != TxStatus.SUCCESS) {
            throw new IritaSDKException(String.format("log: %s\nhash: %s", resultTx.getLog(), Optional.of(resultTx).map(ResultTx::getResult).map(Result::getHash).orElse("")));
        }
        return resultTx;
    }

    public ResultQueryTx queryTx(String hash) throws IOException, NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Map<String, Object> params = new HashMap<>();
        params.put("prove", true);
        params.put("hash", Base64.getEncoder().encodeToString(Hex.decode(hash)));
        JsonRpc jsonRpc = JsonRpc.WrapBaseQuery(params, "tx");
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String str = httpUtils.post(rpcUri, mapper.writeValueAsString(jsonRpc));
        TxRpc txRpc = mapper.readValue(str, TxRpc.class);
        Objects.requireNonNull(txRpc, "use json deserialize json_rpc_response return null");
        if (txRpc.getError() != null) {
            throw new IritaSDKException(txRpc.getError().getData());
        }
        return parseResultQueryTx(txRpc.getResult());
    }

    public ResultQueryTx parseResultQueryTx(irita.sdk.model.tx.Result result) throws IOException, NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        if (result == null) {
            throw new IritaSDKException("parse ResultQueryTx failed: result can not be null");
        }
        TxOuterClass.Tx tx = TxOuterClass.Tx.parseFrom(Base64.getDecoder().decode(result.getTx()));
        List<GeneratedMessageV3> messageList = new ArrayList<>();
        for (Any any : tx.getBody().getMessagesList()) {
            messageList.add(unpackMsg(any.getTypeUrl(), any.getValue()));
        }

        ResultBlock resultBlock = queryBlock(result.getHeight());

        for (int i = 0; i < result.getTxResult().getEvents().size(); i++) {
            for (int j = 0; j < result.getTxResult().getEvents().get(i).getAttributes().size(); j++) {
                result.getTxResult().getEvents().get(i).getAttributes().get(j).setKey(
                        new String(Base64.getDecoder().decode(result.getTxResult().getEvents().get(i).getAttributes().get(j).getKey())));
                if (StringUtils.isNotEmpty(result.getTxResult().getEvents().get(i).getAttributes().get(j).getValue())) {
                    result.getTxResult().getEvents().get(i).getAttributes().get(j).setValue(
                            new String(Base64.getDecoder().decode(result.getTxResult().getEvents().get(i).getAttributes().get(j).getValue())));
                }
            }
        }
        ResultQueryTx resultQueryTx = new ResultQueryTx();
        resultQueryTx.setHash(result.getHash());
        resultQueryTx.setHeight(result.getHeight());
        resultQueryTx.setResult(result.getTxResult());
        resultQueryTx.setTimeStamp(resultBlock.getBlock().getHeader().getTime());

        Body body = new Body();
        body.setMemo(tx.getBody().getMemo());
        body.setTimeOutHeight(tx.getBody().getTimeoutHeight());
        body.setMsgs(messageList);
        irita.sdk.model.tx.Tx t = new irita.sdk.model.tx.Tx();
        t.setBody(body);
        t.setAuthInfo(tx.getAuthInfo());
        resultQueryTx.setTx(t);
        return resultQueryTx;
    }

    public ResultSearchTxs queryTxs(EventQueryBuilder builder, int page, int size) throws IOException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        String query = builder.build();
        if (StringUtils.isEmpty(query)) {
            throw new IritaSDKException("must declare at least one tag to search");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("prove", true);
        params.put("order_by", "asc");
        if (page > 0) {
            params.put("page", String.valueOf(page));
        }
        if (size > 0) {
            params.put("per_page", String.valueOf(size));
        }
        JsonRpc jsonRpc = JsonRpc.WrapBaseQuery(params, "tx_search");
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String str = httpUtils.post(rpcUri, mapper.writeValueAsString(jsonRpc));
        TxsRpc txsRpc = mapper.readValue(str, TxsRpc.class);
        Objects.requireNonNull(txsRpc, "use json deserialize json_rpc_response return null");
        if (txsRpc.getError() != null) {
            throw new IritaSDKException(txsRpc.getError().getData());
        }

        ResultSearchTxs resultSearchTxs = new ResultSearchTxs();
        resultSearchTxs.setTotal(txsRpc.getResult().getTotalCount());
        if (txsRpc.getResult().getTxs().size() > 0) {
            List<ResultQueryTx> list = new ArrayList<>(txsRpc.getResult().getTxs().size());
            for (irita.sdk.model.tx.Result result : txsRpc.getResult().getTxs()) {
                list.add(parseResultQueryTx(result));
            }
            resultSearchTxs.setTxs(list);
        }
        return resultSearchTxs;
    }

    public GeneratedMessageV3 unpackMsg(String typeUrl, ByteString value) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (StringUtils.isEmpty(typeUrl) || value.isEmpty()) {
            throw new IritaSDKException("message can not be empty");
        }
        typeUrl = typeUrl.replace("/", "");
        Class<?> clazz = MsgEnum.getClassName(typeUrl);
        Method method = clazz.getMethod("parseFrom", ByteString.class);
        return (GeneratedMessageV3) method.invoke(clazz, value);
    }

    public ResultBlock queryBlock(String height) throws IOException, NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Map<String, Object> params = new HashMap<>();
        params.put("height", height);

        JsonRpc jsonRpc = JsonRpc.WrapBaseQuery(params, "block");
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String str = httpUtils.post(rpcUri, mapper.writeValueAsString(jsonRpc));
        ResultBlockRpc resultBlock = mapper.readValue(str, ResultBlockRpc.class);
        Objects.requireNonNull(resultBlock, "use json deserialize json_rpc_response return null");
        if (resultBlock.getError() != null) {
            throw new IritaSDKException(resultBlock.getError().getData());
        }
        List<StdTx> stdTxList = new ArrayList<>();
        StdTx stdTx;
        List<GeneratedMessageV3> messageList;
        if (resultBlock.getResult().getBlock().getData().getTxs() != null && resultBlock.getResult().getBlock().getData().getTxs().size() > 0) {
            for (Object o : resultBlock.getResult().getBlock().getData().getTxs()) {
                stdTx = new StdTx();
                TxOuterClass.Tx tx = TxOuterClass.Tx.parseFrom(Base64.getDecoder().decode((String) o));
                messageList = new ArrayList<>();
                for (Any any : tx.getBody().getMessagesList()) {
                    messageList.add(unpackMsg(any.getTypeUrl(), any.getValue()));
                }
                stdTx.setMsgs(messageList);
                stdTx.setMemo(tx.getBody().getMemo());
                stdTx.setFee(tx.getAuthInfo().getFee());
                stdTx.setTxHash(Hex.toHexString(HashUtils.sha256(Base64.getDecoder().decode((String) o))));
                stdTxList.add(stdTx);
            }
        }
        resultBlock.getResult().getBlock().getData().setTxs(stdTxList);
        return resultBlock.getResult();
    }

    public BlockResult queryBlockResult(String height) throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("height", height);
        JsonRpc jsonRpc = JsonRpc.WrapBaseQuery(params, "block_results");
        ObjectMapper mapper = new ObjectMapper();
        String str = httpUtils.post(rpcUri, mapper.writeValueAsString(jsonRpc));
        ResultBlockResults resultBlockResults = mapper.readValue(str, ResultBlockResults.class);
        Objects.requireNonNull(resultBlockResults, "use json deserialize json_rpc_response return null");
        if (resultBlockResults.getError() != null) {
            throw new IritaSDKException(resultBlockResults.getError().getData());
        }
        if (resultBlockResults.getResult().getTxsResults() != null && resultBlockResults.getResult().getTxsResults().size() > 0) {
            BlockResult result = resultBlockResults.getResult();
            for (int n = 0; n < result.getTxsResults().size(); n++) {
                // TODO refactor this
                if (result.getTxsResults().get(n).getEvents().size() > 0) {
                    for (int i = 0; i < result.getTxsResults().get(n).getEvents().size(); i++) {
                        for (int j = 0; j < result.getTxsResults().get(n).getEvents().get(i).getAttributes().size(); j++) {
                            result.getTxsResults().get(n).getEvents().get(i).getAttributes().get(j).setKey(
                                    new String(Base64.getDecoder().decode(result.getTxsResults().get(n).getEvents().get(i).getAttributes().get(j).getKey())));
                            result.getTxsResults().get(n).getEvents().get(i).getAttributes().get(j).setValue(
                                    new String(Base64.getDecoder().decode(result.getTxsResults().get(n).getEvents().get(i).getAttributes().get(j).getValue())));
                        }
                    }
                }
            }
        }
        return resultBlockResults.getResult();
    }
}

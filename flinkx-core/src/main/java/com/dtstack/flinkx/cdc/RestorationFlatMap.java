package com.dtstack.flinkx.cdc;

import com.dtstack.flinkx.cdc.store.Fetcher;
import com.dtstack.flinkx.cdc.store.Monitor;
import com.dtstack.flinkx.cdc.store.Store;
import com.dtstack.flinkx.cdc.worker.WorkerManager;
import com.dtstack.flinkx.element.ColumnRowData;

import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.data.RowData;
import org.apache.flink.util.Collector;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author tiezhu@dtstack.com
 * @since 2021/12/1 星期三
 *     <p>数据（不论ddl还是dml数据）下发到对应表名下的unblock队列中，worker在轮询过程中，
 *     处理unblock数据队列中的数据，在遇到ddl数据之后，将数据队列置为block状态，并将队
 *     列引用交给store处理，store在拿到队列引用之后，将队列头部的ddl数据下发到外部存储中, 并监听外部存储对ddl的反馈情况（监听工作由store中额外的线程来执行），
 *     此时，队列仍然处于block状态；在收到外部存储的反馈之后，将数据队列头部的ddl数据移除，同时将队列状 态回归为unblock状态，队列引用还给worker。
 */
public class RestorationFlatMap extends RichFlatMapFunction<RowData, RowData> {

    private final ConcurrentHashMap<String, Deque<RowData>> blockedQueues =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Deque<RowData>> unblockQueues =
            new ConcurrentHashMap<>();

    private final Monitor monitor;

    private final WorkerManager workerManager;

    public RestorationFlatMap(Fetcher fetcher, Store store) {
        this.monitor = new Monitor(fetcher, store, blockedQueues, unblockQueues);
        this.workerManager = new WorkerManager(unblockQueues, blockedQueues);
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        workerManager.open();
        monitor.open();
    }

    @Override
    public void close() throws Exception {
        workerManager.close();
        monitor.close();
    }

    @Override
    public void flatMap(RowData value, Collector<RowData> out) throws Exception {
        put(value);
        workerManager.work(out);
        monitor.work();
    }

    private void put(RowData rowData) {
        String tableIdentifier;
        if (rowData instanceof ColumnRowData) {
            tableIdentifier = getTableIdentifierFromColumnData((ColumnRowData) rowData);
        } else {
            tableIdentifier = ((DdlRowData) rowData).getTableIdentifier();
        }
        putRowData(rowData, tableIdentifier);
    }

    /**
     * 从dml ColumnRowData 获取对应的tableIdentifier
     *
     * @param data column row data.
     * @return table identifier.
     */
    private String getTableIdentifierFromColumnData(ColumnRowData data) {
        String[] headers = data.getHeaders();
        int schemaIndex = 0;
        int tableIndex = 0;
        for (int i = 0; i < Objects.requireNonNull(headers).length; i++) {
            if ("schema".equalsIgnoreCase(headers[i])) {
                schemaIndex = i;
                continue;
            }
            if ("table".equalsIgnoreCase(headers[i])) {
                tableIndex = i;
            }
        }
        String schema = data.getString(schemaIndex).toString();
        String table = data.getString(tableIndex).toString();
        return schema + "." + table;
    }

    /**
     * 将row data 放入队列中，如果队列中没有对应的数据队列，那么创建一个。
     *
     * @param data row data.
     * @param tableIdentifier table identifier.
     */
    private void putRowData(RowData data, String tableIdentifier) {
        if (!unblockQueues.containsKey(tableIdentifier)
                && !unblockQueues.containsKey(tableIdentifier)) {
            // 说明此时不存在该tableIdentifier的数据队列
            Deque<RowData> dataDeque = new LinkedList<>();
            dataDeque.addFirst(data);
            unblockQueues.put(tableIdentifier, dataDeque);
        } else if (unblockQueues.containsKey(tableIdentifier)) {
            unblockQueues.get(tableIdentifier).push(data);
        } else {
            blockedQueues.get(tableIdentifier).push(data);
        }
    }
}

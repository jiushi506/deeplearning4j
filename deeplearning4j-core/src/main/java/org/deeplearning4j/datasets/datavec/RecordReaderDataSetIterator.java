/*-
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.datasets.datavec;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.datavec.api.io.WritableConverter;
import org.datavec.api.io.converters.SelfWritableConverter;
import org.datavec.api.io.converters.WritableConverterException;
import org.datavec.api.records.Record;
import org.datavec.api.records.metadata.RecordMetaData;
import org.datavec.api.records.reader.RecordReader;
import org.datavec.api.records.reader.SequenceRecordReader;
import org.datavec.api.writable.Writable;
import org.datavec.common.data.NDArrayWritable;
import org.deeplearning4j.datasets.parallel.Parallel;
import org.deeplearning4j.exception.DL4JInvalidInputException;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.DataSetPreProcessor;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.util.FeatureUtil;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Record reader dataset iterator
 *
 * @author Adam Gibson
 */
@Slf4j
public class RecordReaderDataSetIterator implements DataSetIterator {
    protected RecordReader recordReader;
    protected WritableConverter converter;
    protected int batchSize = 10;
    protected int maxNumBatches = -1;
    protected int batchNum = 0;
    protected int labelIndex = -1;
    protected int labelIndexTo = -1;
    protected int numPossibleLabels = -1;
    protected Iterator<List<Writable>> sequenceIter;
    protected DataSet last;
    protected boolean useCurrent = false;
    protected boolean regression = false;
    @Getter
    protected DataSetPreProcessor preProcessor;

    @Getter
    @Setter
    private boolean collectMetaData = false;


    private static final int NUM_CORES = Runtime.getRuntime().availableProcessors();
    private ExecutorService taskExecutor = Executors.newFixedThreadPool( NUM_CORES==1 ? NUM_CORES : NUM_CORES-1 );

    public RecordReaderDataSetIterator(RecordReader recordReader, WritableConverter converter, int batchSize) {
        this(recordReader, converter, batchSize, -1,
                recordReader.getLabels() == null ? -1 : recordReader.getLabels().size());
    }

    public RecordReaderDataSetIterator(RecordReader recordReader, int batchSize) {
        this(recordReader, new SelfWritableConverter(), batchSize, -1,
                recordReader.getLabels() == null ? -1 : recordReader.getLabels().size());
    }

    /**
     * Main constructor for classification. This will convert the input class index (at position labelIndex, with integer
     * values 0 to numPossibleLabels-1 inclusive) to the appropriate one-hot output/labels representation.
     *
     * @param recordReader         RecordReader: provides the source of the data
     * @param batchSize            Batch size (number of examples) for the output DataSet objects
     * @param labelIndex           Index of the label Writable (usually an IntWritable), as obtained by recordReader.next()
     * @param numPossibleLabels    Number of classes (possible labels) for classification
     */
    public RecordReaderDataSetIterator(RecordReader recordReader, int batchSize, int labelIndex,
                                       int numPossibleLabels) {
        this(recordReader, new SelfWritableConverter(), batchSize, labelIndex, numPossibleLabels);
    }

    public RecordReaderDataSetIterator(RecordReader recordReader, WritableConverter converter, int batchSize,
                                       int labelIndex, int numPossibleLabels, boolean regression) {
        this(recordReader, converter, batchSize, labelIndex, numPossibleLabels, -1, regression);
    }

    public RecordReaderDataSetIterator(RecordReader recordReader, WritableConverter converter, int batchSize,
                                       int labelIndex, int numPossibleLabels) {
        this(recordReader, converter, batchSize, labelIndex, numPossibleLabels, -1, false);
    }

    public RecordReaderDataSetIterator(RecordReader recordReader, int batchSize, int labelIndex, int numPossibleLabels,
                                       int maxNumBatches) {
        this(recordReader, new SelfWritableConverter(), batchSize, labelIndex, numPossibleLabels, maxNumBatches, false);
    }

    /**
     * Main constructor for multi-label regression (i.e., regression with multiple outputs)
     *
     * @param recordReader      RecordReader to get data from
     * @param labelIndexFrom    Index of the first regression target
     * @param labelIndexTo      Index of the last regression target, inclusive
     * @param batchSize         Minibatch size
     * @param regression        Require regression = true. Mainly included to avoid clashing with other constructors previously defined :/
     */
    public RecordReaderDataSetIterator(RecordReader recordReader, int batchSize, int labelIndexFrom, int labelIndexTo,
                                       boolean regression) {
        this(recordReader, new SelfWritableConverter(), batchSize, labelIndexFrom, labelIndexTo, -1, -1, regression);
    }


    public RecordReaderDataSetIterator(RecordReader recordReader, WritableConverter converter, int batchSize,
                                       int labelIndex, int numPossibleLabels, int maxNumBatches, boolean regression) {
        this(recordReader, converter, batchSize, labelIndex, labelIndex, numPossibleLabels, maxNumBatches, regression);
    }


    /**
     * Main constructor
     *
     * @param recordReader      the recordreader to use
     * @param converter         the batch size
     * @param maxNumBatches     Maximum number of batches to return
     * @param labelIndexFrom    the index of the label (for classification), or the first index of the labels for multi-output regression
     * @param labelIndexTo      only used if regression == true. The last index _inclusive_ of the multi-output regression
     * @param numPossibleLabels the number of possible labels for classification. Not used if regression == true
     * @param regression        if true: regression. If false: classification (assume labelIndexFrom is a
     */
    public RecordReaderDataSetIterator(RecordReader recordReader, WritableConverter converter, int batchSize,
                                       int labelIndexFrom, int labelIndexTo, int numPossibleLabels, int maxNumBatches,
                                       boolean regression) {
        this.recordReader = recordReader;
        this.converter = converter;
        this.batchSize = batchSize;
        this.maxNumBatches = maxNumBatches;
        this.labelIndex = labelIndexFrom;
        this.labelIndexTo = labelIndexTo;
        this.numPossibleLabels = numPossibleLabels;
        this.regression = regression;
    }


    @Override
    public DataSet next(int num) {
        if (useCurrent) {
            useCurrent = false;
            if (preProcessor != null)
                preProcessor.preProcess(last);
            return last;
        }

        final ArrayList<List<Writable>> writeables = new ArrayList<>();
        final ArrayList<Record> records = new ArrayList<>();

        // below we want IO from record reader to be sequential (for UX)
        // however, conversion is agnostic and we then parallelize further operations
        // it's up to record readers to implement prefetch to speed up ops
        for(int i = 0; i < num; i++) {
            if(hasNext()) {
                if (recordReader instanceof SequenceRecordReader) {
                    if (sequenceIter == null || !sequenceIter.hasNext()) {
                        List<List<Writable>> sequenceRecord = ((SequenceRecordReader) recordReader).sequenceRecord();
                        sequenceIter = sequenceRecord.iterator();
                    }
                    writeables.add(sequenceIter.next());

                } else {
                    if (collectMetaData) {
                        records.add(recordReader.nextRecord());
                    } else {
                        writeables.add(recordReader.next());
                    }
                }
            } else {
                break;
            }
        }

        final int numLoops = collectMetaData ? records.size() : writeables.size();
        final DataSet[] dataSetArray = new DataSet[numLoops];
        final RecordMetaData[] metaArray = new RecordMetaData[numLoops];

        // here we parallelize our for loop to speed up underlying bottlenecks in array conversion
        // num is passed to specify how many loops will be run
        Parallel.For(
            numLoops,
            taskExecutor,
            new Parallel.Operation() {
                public void perform(int i) {
                    if (recordReader instanceof SequenceRecordReader) {
                        DataSet d = getDataSet(writeables.get(i));
                        if(d != null)
                            dataSetArray[i] = d;
                    } else {
                        if (collectMetaData) {
                            Record record = records.get(i);
                            DataSet d = getDataSet(record.getRecord());
                            if (d != null) {
                                dataSetArray[i] = d;
                                metaArray[i] = record.getMetaData();
                            }
                        } else {
                            try {
                                DataSet d = getDataSet(writeables.get(i));
                                if (d != null)
                                    dataSetArray[i] = d;
                            } catch (Exception e) {
                                log.warn("Unable to get dataset ...skipping", e);
                            }
                        }
                    }
                }
            }
        );
        batchNum++;

        // convert arrays to return types
        final List<DataSet> dataSets = new ArrayList<>(Arrays.asList(dataSetArray));
        final List<RecordMetaData> meta = (collectMetaData ? new ArrayList<>(Arrays.asList(metaArray)) : null);

        // fix for imbalanced batches // TODO: does this impact performance?
        dataSets.removeAll(Collections.singleton(null));
        if(meta != null) meta.removeAll(Collections.singleton(null));

        if (dataSets.isEmpty()) {
            return null;
        }

        DataSet ret = DataSet.merge(dataSets);
        if (collectMetaData) {
            ret.setExampleMetaData(meta);
        }
        last = ret;
        if (preProcessor != null)
            preProcessor.preProcess(ret);
        //Add label name values to dataset
        if (recordReader.getLabels() != null)
            ret.setLabelNames(recordReader.getLabels());
        return ret;
    }


    private DataSet getDataSet(List<Writable> record) {
        if(record == null)
            return null;

        List<Writable> currList;
        if (record instanceof List)
            currList = record;
        else
            currList = new ArrayList<>(record);

        //allow people to specify label index as -1 and infer the last possible label
        if (numPossibleLabels >= 1 && labelIndex < 0) {
            labelIndex = record.size() - 1;
        }

        INDArray label = null;
        INDArray featureVector = null;
        int featureCount = 0;
        int labelCount = 0;

        //no labels
        if (currList.size() == 2 && currList.get(1) instanceof NDArrayWritable
                && currList.get(0) instanceof NDArrayWritable && currList.get(0) == currList.get(1)) {
            NDArrayWritable writable = (NDArrayWritable) currList.get(0);
            return new DataSet(writable.get(), writable.get());
        }
        if (currList.size() == 2 && currList.get(0) instanceof NDArrayWritable) {
            if (!regression) {
                label = FeatureUtil.toOutcomeVector((int) Double.parseDouble(currList.get(1).toString()),
                        numPossibleLabels);
            } else {
                if (currList.get(1) instanceof NDArrayWritable) {
                    label = ((NDArrayWritable) currList.get(1)).get();
                } else {
                    label = Nd4j.scalar(currList.get(1).toDouble());
                }
            }
            NDArrayWritable ndArrayWritable = (NDArrayWritable) currList.get(0);
            featureVector = ndArrayWritable.get();
            return new DataSet(featureVector, label);
        }

        for (int j = 0; j < currList.size(); j++) {
            Writable current = currList.get(j);
            //ndarray writable is an insane slow down herecd
            if (!(current instanceof NDArrayWritable) && current.toString().isEmpty())
                continue;

            if (regression && j == labelIndex && j == labelIndexTo && current instanceof NDArrayWritable) {
                //Case: NDArrayWritable for the labels
                label = ((NDArrayWritable) current).get();
            } else if (regression && j >= labelIndex && j <= labelIndexTo) {
                //This is the multi-label regression case
                if (label == null)
                    label = Nd4j.create(1, (labelIndexTo - labelIndex + 1));
                label.putScalar(labelCount++, current.toDouble());
            } else if (labelIndex >= 0 && j == labelIndex) {
                //single label case (classification, etc)
                if (converter != null)
                    try {
                        current = converter.convert(current);
                    } catch (WritableConverterException e) {
                        e.printStackTrace();
                    }
                if (numPossibleLabels < 1)
                    throw new IllegalStateException("Number of possible labels invalid, must be >= 1");
                if (regression) {
                    label = Nd4j.scalar(current.toDouble());
                } else {
                    int curr = current.toInt();
                    if (curr < 0 || curr >= numPossibleLabels) {
                        throw new DL4JInvalidInputException(
                                "Invalid classification data: expect label value (at label index column = "
                                        + labelIndex + ") to be in range 0 to "
                                        + (numPossibleLabels - 1)
                                        + " inclusive (0 to numClasses-1, with numClasses="
                                        + numPossibleLabels + "); got label value of " + current);
                    }
                    label = FeatureUtil.toOutcomeVector(curr, numPossibleLabels);
                }
            } else {
                try {
                    double value = current.toDouble();
                    if (featureVector == null) {
                        if (regression && labelIndex >= 0) {
                            //Handle the possibly multi-label regression case here:
                            int nLabels = labelIndexTo - labelIndex + 1;
                            featureVector = Nd4j.create(1, currList.size() - nLabels);
                        } else {
                            //Classification case, and also no-labels case
                            featureVector = Nd4j.create(labelIndex >= 0 ? currList.size() - 1 : currList.size());
                        }
                    }
                    featureVector.putScalar(featureCount++, value);
                } catch (UnsupportedOperationException e) {
                    // This isn't a scalar, so check if we got an array already
                    if (current instanceof NDArrayWritable) {
                        assert featureVector == null;
                        featureVector = ((NDArrayWritable) current).get();
                    } else {
                        throw e;
                    }
                }
            }
        }

        return new DataSet(featureVector, labelIndex >= 0 ? label : featureVector);
    }

    @Override
    public int totalExamples() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int inputColumns() {
        if (last == null) {
            DataSet next = next();
            last = next;
            useCurrent = true;
            return next.numInputs();
        } else
            return last.numInputs();

    }

    @Override
    public int totalOutcomes() {
        if (last == null) {
            DataSet next = next();
            last = next;
            useCurrent = true;
            return next.numOutcomes();
        } else
            return last.numOutcomes();


    }

    @Override
    public boolean resetSupported() {
        return true;
    }

    @Override
    public boolean asyncSupported() {
        return true;
    }

    @Override
    public void reset() {
        batchNum = 0;
        recordReader.reset();
    }

    @Override
    public int batch() {
        return batchSize;
    }

    @Override
    public int cursor() {
        throw new UnsupportedOperationException();

    }

    @Override
    public int numExamples() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPreProcessor(org.nd4j.linalg.dataset.api.DataSetPreProcessor preProcessor) {
        this.preProcessor = preProcessor;
    }

    @Override
    public boolean hasNext() {
        return (recordReader.hasNext() && (maxNumBatches < 0 || batchNum < maxNumBatches));
    }

    @Override
    public DataSet next() {
        return next(batchSize);
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> getLabels() {
        return recordReader.getLabels();
    }

    /**
     * Load a single example to a DataSet, using the provided RecordMetaData.
     * Note that it is more efficient to load multiple instances at once, using {@link #loadFromMetaData(List)}
     *
     * @param recordMetaData RecordMetaData to load from. Should have been produced by the given record reader
     * @return DataSet with the specified example
     * @throws IOException If an error occurs during loading of the data
     */
    public DataSet loadFromMetaData(RecordMetaData recordMetaData) throws IOException {
        return loadFromMetaData(Collections.singletonList(recordMetaData));
    }

    /**
     * Load a multiple examples to a DataSet, using the provided RecordMetaData instances.
     *
     * @param list List of RecordMetaData instances to load from. Should have been produced by the record reader provided
     *             to the RecordReaderDataSetIterator constructor
     * @return DataSet with the specified examples
     * @throws IOException If an error occurs during loading of the data
     */
    public DataSet loadFromMetaData(List<RecordMetaData> list) throws IOException {
        List<Record> records = recordReader.loadFromMetaData(list);
        List<DataSet> dataSets = new ArrayList<>();
        List<RecordMetaData> meta = new ArrayList<>();
        for (Record r : records) {
            dataSets.add(getDataSet(r.getRecord()));
            meta.add(r.getMetaData());
        }

        if (dataSets.isEmpty()) {
           return null;
        }

        DataSet ret = DataSet.merge(dataSets);
        ret.setExampleMetaData(meta);
        last = ret;
        if (preProcessor != null)
            preProcessor.preProcess(ret);
        if (recordReader.getLabels() != null)
            ret.setLabelNames(recordReader.getLabels());
        return ret;
    }

    public void shutdown() { taskExecutor.shutdown(); }
}

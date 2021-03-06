package com.cloudera.labs.envelope.plan;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.avro.Schema.Field;
import org.apache.avro.generic.GenericRecord;

import com.cloudera.labs.envelope.RecordModel;
import com.cloudera.labs.envelope.utils.RecordUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * A planner implementation for storing all versions of the values of a key (its history) using
 * Type II SCD modeling.
 */
public class HistoryPlanner extends Planner {
    
    private String CURRENT_FLAG_YES = "Y";
    private String CURRENT_FLAG_NO = "N";
    private Long FAR_FUTURE_MILLIS = 253402214400000L; // 9999-12-31
    
    public HistoryPlanner(Properties props) {
        super(props);
    }
    
    @Override
    public List<PlannedRecord> planMutations(List<GenericRecord> arrivingRecords,
            List<GenericRecord> existingRecords, RecordModel recordModel)
    {
        List<String> keyFieldNames = recordModel.getKeyFieldNames();
        String timestampFieldName = recordModel.getTimestampFieldName();
        List<String> valueFieldNames = recordModel.getValueFieldNames();
        String effectiveFromFieldName = recordModel.getEffectiveFromFieldName();
        String effectiveToFieldName = recordModel.getEffectiveToFieldName();
        String currentFlagFieldName = recordModel.getCurrentFlagFieldName();
        String lastUpdatedFieldName = recordModel.getLastUpdatedFieldName();
        
        Comparator<GenericRecord> tc = new RecordUtils.TimestampComparator(timestampFieldName);
        
        Map<GenericRecord, List<GenericRecord>> arrivedByKey = RecordUtils.recordsByKey(arrivingRecords, keyFieldNames);
        Map<GenericRecord, List<GenericRecord>> existingByKey = RecordUtils.recordsByKey(existingRecords, keyFieldNames);
        
        List<PlannedRecord> planned = Lists.newArrayList();
        List<PlannedRecord> plannedForKey = Lists.newArrayList();
        
        for (Map.Entry<GenericRecord, List<GenericRecord>> arrivingByKey : arrivedByKey.entrySet()) {
            GenericRecord key = arrivingByKey.getKey();
            List<GenericRecord> arrivingForKey = arrivingByKey.getValue();
            List<GenericRecord> existingForKey = existingByKey.get(key);
            
            plannedForKey.clear();
            if (existingForKey != null) {
                for (GenericRecord existing : existingForKey) {
                    plannedForKey.add(new PlannedRecord(existing, MutationType.NONE));
                }
            }
            
            Collections.sort(plannedForKey, tc);
            
            for (GenericRecord arrived : arrivingForKey) {
                Long arrivedTimestamp = (Long)arrived.get(timestampFieldName);
                
                // There was no existing record for the key, so we just insert the input record.
                if (plannedForKey.size() == 0) {
                    arrived.put(effectiveFromFieldName, arrivedTimestamp);
                    arrived.put(effectiveToFieldName, FAR_FUTURE_MILLIS);
                    if (recordModel.hasCurrentFlagField()) {
                        arrived.put(currentFlagFieldName, CURRENT_FLAG_YES);
                    }
                    if (recordModel.hasLastUpdatedField()) {
                        arrived.put(lastUpdatedFieldName, currentTimestampString());
                    }
                    plannedForKey.add(new PlannedRecord(arrived, MutationType.INSERT));
                }
                
                // Iterate through each existing record of the key in time order, stopping when we
                // have either corrected the history or gone all the way through it.
                for (int position = 0; position < plannedForKey.size(); position++) {
                    PlannedRecord plan = plannedForKey.get(position);
                    Long planTimestamp = (Long)plan.get(timestampFieldName);
                    PlannedRecord previousPlanned = null;
                    PlannedRecord nextPlanned = null;
                    Long nextPlannedTimestamp = null;
                    
                    if (position > 0) {
                        previousPlanned = plannedForKey.get(position - 1);
                    }
                    if (position + 1 < plannedForKey.size()) {
                        nextPlanned = plannedForKey.get(position + 1);
                        nextPlannedTimestamp = (Long)nextPlanned.get(timestampFieldName);
                    }
                    
                    // There is an existing record for the same key and timestamp. It is possible that
                    // the existing record is in the storage layer or is about to be added during this
                    // micro-batch. Either way, we only update that record if it has changed.
                    if (RecordUtils.simultaneous(arrived, plan, timestampFieldName) &&
                        RecordUtils.different(arrived, plan, valueFieldNames))
                    {
                        if (recordModel.hasLastUpdatedField()) {
                            arrived.put(lastUpdatedFieldName, currentTimestampString());
                        }
                        
                        if (plan.getMutationType().equals(MutationType.INSERT)) {
                            plannedForKey.add(new PlannedRecord(arrived, MutationType.INSERT));
                        }
                        else {
                            plannedForKey.add(new PlannedRecord(arrived, MutationType.UPDATE));
                        }
                        plannedForKey.remove(plan);
                        
                        break;
                    }
                    // Before them all
                    // -> Insert with ED just before first
                    // The input record is timestamped before any existing record of the same key. In
                    // this case there is no need to modify existing records, and we only have to insert
                    // the input record as effective up until just prior to the first existing record.
                    else if (previousPlanned == null && RecordUtils.before(arrived, plan, timestampFieldName)) {
                        arrived.put(effectiveFromFieldName, arrivedTimestamp);
                        arrived.put(effectiveToFieldName, RecordUtils.precedingTimestamp(planTimestamp));
                        if (recordModel.hasCurrentFlagField()) {
                            arrived.put(currentFlagFieldName, CURRENT_FLAG_NO);
                        }
                        if (recordModel.hasLastUpdatedField()) {
                            arrived.put(lastUpdatedFieldName, currentTimestampString());
                        }
                        plannedForKey.add(new PlannedRecord(arrived, MutationType.INSERT));
                        
                        break;
                    }
                    // The input record is timestamped with an existing record of the same key before it
                    // and an existing record of the same key after it. We insert the input record
                    // effective until just prior to the next existing record and we update the
                    // previous existing record to be effective until just prior to the input record.
                    else if (plan != null && nextPlanned != null &&
                             RecordUtils.after(arrived, plan, timestampFieldName) &&
                             RecordUtils.before(arrived, nextPlanned, timestampFieldName))
                    {
                        arrived.put(effectiveFromFieldName, arrivedTimestamp);
                        arrived.put(effectiveToFieldName, RecordUtils.precedingTimestamp(nextPlannedTimestamp));
                        if (recordModel.hasCurrentFlagField()) {
                            arrived.put(currentFlagFieldName, CURRENT_FLAG_NO);
                        }
                        if (recordModel.hasLastUpdatedField()) {
                            arrived.put(lastUpdatedFieldName, currentTimestampString());
                        }
                        carryForwardWhenNull(arrived, plan);
                        plannedForKey.add(new PlannedRecord(arrived, MutationType.INSERT));
                        
                        plan.put(effectiveFromFieldName, RecordUtils.precedingTimestamp(arrivedTimestamp));
                        if (recordModel.hasCurrentFlagField()) {
                            plan.put(currentFlagFieldName, CURRENT_FLAG_NO);
                        }
                        if (recordModel.hasLastUpdatedField()) {
                            plan.put(lastUpdatedFieldName, currentTimestampString());
                        }
                        if (!plan.getMutationType().equals(MutationType.INSERT)) {
                            plan.setMutationType(MutationType.UPDATE);
                        }
                        
                        break;
                    }
                    // The input record is arriving after all existing records of the same key. This
                    // is the 'normal' case where data arrives in order. We insert the input record
                    // effective until the far future, and we update the previous existing record
                    // to be effective until just prior to the input record.
                    else if (RecordUtils.after(arrived, plan, timestampFieldName) && nextPlanned == null) {
                        arrived.put(effectiveFromFieldName, arrivedTimestamp);
                        arrived.put(effectiveToFieldName, FAR_FUTURE_MILLIS);
                        if (recordModel.hasCurrentFlagField()) {
                            arrived.put(currentFlagFieldName, CURRENT_FLAG_YES);
                        }
                        if (recordModel.hasLastUpdatedField()) {
                            arrived.put(lastUpdatedFieldName, currentTimestampString());
                        }
                        carryForwardWhenNull(arrived, plan);
                        plannedForKey.add(new PlannedRecord(arrived, MutationType.INSERT));
                        
                        plan.put(effectiveToFieldName, RecordUtils.precedingTimestamp(arrivedTimestamp));
                        if (recordModel.hasCurrentFlagField()) {
                            plan.put(currentFlagFieldName, CURRENT_FLAG_NO);
                        }
                        if (recordModel.hasLastUpdatedField()) {
                            plan.put(lastUpdatedFieldName, currentTimestampString());
                        }
                        if (!plan.getMutationType().equals(MutationType.INSERT)) {
                            plan.setMutationType(MutationType.UPDATE);
                        }
                        
                        break;
                    }
                }
                
                // TODO: don't re-sort if there was no change
                // TODO: don't re-sort if there are no more input records for the key
                Collections.sort(plannedForKey, tc);
            }
            
            for (PlannedRecord plan : plannedForKey) {
                if (!plan.getMutationType().equals(MutationType.NONE))
                {
                    planned.add(plan);
                }
            }
        }
        
        return planned;
    }
    
    // When the arrived record value is null then we have the option to carry forward
    // the value from the previous record. This is useful for handling sparse stream records.
    private void carryForwardWhenNull(GenericRecord arrived, GenericRecord previous) {
        if (Boolean.parseBoolean(props.getProperty("carry.forward.when.null", "false"))) {
            for (Field field : arrived.getSchema().getFields()) {
                String fieldName = field.name();
                if (arrived.get(fieldName) == null && previous.get(fieldName) != null) {
                    arrived.put(fieldName, previous.get(fieldName));
                }
            }
        }
    }
    
    @Override
    public boolean requiresExistingRecords() {
        return true;
    }
    
    @Override
    public boolean requiresKeyColocation() {
        return true;
    }
    
    @Override
    public Set<MutationType> getEmittedMutationTypes() {
        return Sets.newHashSet(MutationType.INSERT, MutationType.UPDATE);
    }
    
}

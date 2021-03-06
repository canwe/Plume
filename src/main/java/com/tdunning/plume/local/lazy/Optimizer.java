/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tdunning.plume.local.lazy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tdunning.plume.DoFn;
import com.tdunning.plume.EmitFn;
import com.tdunning.plume.PCollection;
import com.tdunning.plume.local.lazy.op.CombineValues;
import com.tdunning.plume.local.lazy.op.DeferredOp;
import com.tdunning.plume.local.lazy.op.Flatten;
import com.tdunning.plume.local.lazy.op.GroupByKey;
import com.tdunning.plume.local.lazy.op.MultipleParallelDo;
import com.tdunning.plume.local.lazy.op.OneToOneOp;
import com.tdunning.plume.local.lazy.op.ParallelDo;

/**
 * First version of Optimizer.
 * 
 * Known limitations:
 * 
 * - It doesn't add "bypass-inputs" to MSCRs. A Bypass input can be simulated by doing a dummy groupBy() 
 *  that doesn't do nothing.
 */
public class Optimizer {

  public ExecutionStep optimize(PlumeWorkflow workFlow) {
    workFlow.build();
    List<PCollection> inputs = workFlow.getInputs();
    List<PCollection> outputs = workFlow.getOutputs();
    return optimize(inputs, outputs);
  }
  
  /**
   * Optimizes an execution tree
   * 
   * @param inputs   A list of the inputs.
   * @param outputs  A list of the outputs.
   * @return  An optimized dataflow that consists of MSCR operations decorated with functional
   * compositions.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public ExecutionStep optimize(List<PCollection> inputs, List<PCollection> outputs) {
    if(outputs == null || outputs.size() == 0) {
      throw new IllegalArgumentException("Empty output list");
    }
    if(inputs == null || inputs.size() == 0) {
      throw new IllegalArgumentException("Empty input list");
    }
    for(PCollection output: outputs) {
      sinkFlattens(output);
    }
    for(PCollection output: outputs) {
      fuseParallelDos(output);
    }
    for(PCollection output: outputs) {
      fuseSiblingParallelDos(output);
    }
    // Clean optimized tree
    for(PCollection input: inputs) {
      removeUnnecessaryOps(input, outputs);
    }
    Set<MSCR> mscrs = OptimizerTools.getMSCRBlocks(outputs);
    // Build a map of output -> MSCR step
    Map<PCollection<?>, MSCR> outputMap = new HashMap<PCollection<?>, MSCR>();
    for(MSCR mscr: mscrs) {
      for(Map.Entry<PCollection<?>, MSCR.OutputChannel<?,?,?>> entry: mscr.getOutputChannels().entrySet()) {
        MSCR.OutputChannel<?,?,?> oC = entry.getValue();
        outputMap.put(oC.output, mscr);
      }
    }
    // Calculate dependencies between MSCRs
    Map<MSCR, Set<MSCR>> dependencyMap = new HashMap<MSCR, Set<MSCR>>();
    Set<MSCR> beginningMscrs = new HashSet<MSCR>();
    for(MSCR mscr: mscrs) {
      for(PCollection<?> input: mscr.getInputs()) {
        if(inputs.contains(input)) {
          beginningMscrs.add(mscr);
        }
        MSCR dependsOn = outputMap.get(input);
        if(dependsOn == null) {
          continue;
        }
        Set<MSCR> dependencies = dependencyMap.get(mscr);
        if(dependencies == null) {
          dependencies = new HashSet<MSCR>();
        }
        dependencies.add(dependsOn);
        dependencyMap.put(mscr, dependencies);
      }
    }
    ExecutionStep firstStep = new ExecutionStep();
    for(MSCR step: beginningMscrs) {
      if(dependencyMap.get(step) == null) {
        firstStep.mscrSteps.add(step);
      }
    }
    // Calculate execution plan
    Set<MSCR> solvedSteps = new HashSet<MSCR>();
    solvedSteps.addAll(firstStep.mscrSteps);
    ExecutionStep previousStep = firstStep;
    while(!solvedSteps.containsAll(mscrs)) {
      ExecutionStep nextStep = new ExecutionStep();
      for(MSCR mscr: mscrs) {
        if(solvedSteps.contains(mscr)) {
          continue;
        }
        Set<MSCR> dependencies = dependencyMap.get(mscr);
        if(solvedSteps.containsAll(dependencies)) {
          nextStep.mscrSteps.add(mscr);
          solvedSteps.add(mscr);
        }
      }
      previousStep.nextStep = nextStep;
    }
    return firstStep;
  }
  
  /**
   * Removes unnecesary operations that are not removed by the Optimizer. It goes top-down (receives an Input).
   * Returns true if passed node doesn't lead to an output.
   */
  boolean removeUnnecessaryOps(PCollection arg, List<PCollection> outputs) {
    LazyCollection<?> input = (LazyCollection)arg;
    if(input.getDownOps() == null || input.getDownOps().size() == 0) { // Leaf node
      return !outputs.contains(input);
    }
    List<DeferredOp> finalDOps = new ArrayList<DeferredOp>(); // create new list of deferredops that are 'usefull'
    for(DeferredOp op: input.getDownOps()) {
      boolean remove = false;
      if(op instanceof OneToOneOp) {
        remove = removeUnnecessaryOps(((OneToOneOp<?, ?>)op).getDest(), outputs);
      } else if(op instanceof ParallelDo) {
        remove = removeUnnecessaryOps(((ParallelDo<?, ?>)op).getDest(), outputs);
      } else if(op instanceof Flatten) {
        remove = removeUnnecessaryOps(((Flatten<?>)op).getDest(), outputs);        
      } else if(op instanceof MultipleParallelDo) {
        MultipleParallelDo<?> mPDo = (MultipleParallelDo<?>)op;
        remove = true; // begin with 1 because we will apply an AND gate with the childs
        for(Object entry: mPDo.getDests().entrySet()) {
          PCollection<?> pCol = (PCollection<?>)((Map.Entry)entry).getKey();
          remove = remove & removeUnnecessaryOps(pCol, outputs);
        }
      }
      if(!remove) {
        finalDOps.add(op);
      }
    }
    input.downOps = finalDOps;
    return finalDOps.size() == 0; // if true this node can also be removed
  }
  
  /**
   * Sink flattens pushing them down to create opportunities for ParallelDo fusion
   * @param arg  The collection that may contain flatten operations that we need to sink.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  <T> void sinkFlattens(PCollection<T> arg) {
    LazyCollection<T> output = (LazyCollection<T>)arg;
    if(output.isMaterialized()) { // stop condition for recursive algorithm
      return;
    }
    DeferredOp dOp = output.getDeferredOp();
    if(!(dOp instanceof Flatten)) {
      if(dOp instanceof OneToOneOp) {
        // Recursively apply this function to parent
        sinkFlattens(((OneToOneOp)dOp).getOrigin());
        return;
      } else if(dOp instanceof ParallelDo) {
        // Recursively apply this function to parent
        sinkFlattens(((ParallelDo)dOp).getOrigin());
        return;        
      }
    }
    if(output.getDownOps() == null || output.getDownOps().size() != 1) {
      // Recursively apply this function to parent
      for(Object col: ((Flatten)dOp).getOrigins()) {
        sinkFlattens((PCollection)col);
      }
      return;      
    }
    DeferredOp downOp = output.getDownOps().get(0);
    if(!(downOp instanceof ParallelDo)) {
      return;    
    }
    ParallelDo<T, ?> op = (ParallelDo<T, ?>)downOp; // PDo below current node
    Flatten<T> flatten = (Flatten<T>)dOp; // Flatten above current node
    List<PCollection<?>> newOrigins = new ArrayList<PCollection<?>>();
    // Iterate over all Flatten inputs
    for(PCollection<T> col: flatten.getOrigins()) {
      // Recursively apply this function to this flatten's origin
      LazyCollection<T> fInput = (LazyCollection<T>)col;
      sinkFlattens(fInput);
      // Sink 
      LazyCollection<?> newInput = new LazyCollection();
      newInput.deferredOp = new ParallelDo(op.getFunction(), fInput, newInput);
      newInput.type = ((LazyCollection)flatten.getDest()).getType();
      fInput.downOps.remove(0);
      fInput.addDownOp(newInput.deferredOp);
      newOrigins.add(newInput);
    }
    Flatten<?> newFlatten = new Flatten(newOrigins, op.getDest());
    ((LazyCollection<?>)op.getDest()).deferredOp = newFlatten;
    for(PCollection<?> newOp: newOrigins) {
      ((LazyCollection<?>)newOp).addDownOp(newFlatten);
    }
  }
  
  /**
   * Join ParallelDos that use the same PCollection into multiple-output {@link MultipleParallelDo}
   * @param arg  The original collection that may contain sibling do chains
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  <T> void fuseSiblingParallelDos(PCollection<T> arg) {
    LazyCollection<T> output = (LazyCollection<T>)arg;
    if(output.isMaterialized()) { // stop condition for recursive algorithm
      return;
    }
    DeferredOp dOp = output.getDeferredOp();
    if(!(dOp instanceof ParallelDo)) { // not a ParallelDo
      if(dOp instanceof OneToOneOp) {
        // Recursively apply this function to parent
        fuseSiblingParallelDos(((OneToOneOp)dOp).getOrigin());
        return;
      } 
      if(dOp instanceof Flatten) {
        Flatten<T> flatten = (Flatten)dOp;
        // Recursively apply this function to all parents
        for(PCollection<T> col: flatten.getOrigins()) {
          fuseSiblingParallelDos(col);
        }
        return;
      }
      if(dOp instanceof MultipleParallelDo) {
        return;
      }
    }
    ParallelDo pDo = (ParallelDo)output.getDeferredOp();
    LazyCollection<T> orig = (LazyCollection<T>)pDo.getOrigin();
    int willAdd = 0;
    for(DeferredOp op: orig.getDownOps()) {
      if(op instanceof ParallelDo) {
        willAdd++;
      }
    }
    if(willAdd == 1) { // Parent doesn't have more ParallelDos to fuse
      // Recursively apply this function to parent
      fuseSiblingParallelDos(orig);
      return;
    }
    // MultipleParallelDo is viable, create it
    MultipleParallelDo<T> mPDo = new MultipleParallelDo<T>(orig);
    mPDo.addDest(pDo.getFunction(), output);
    orig.downOps.remove(pDo);
    output.deferredOp = mPDo;
    List<DeferredOp> newList = new ArrayList<DeferredOp>();
    for(DeferredOp op: orig.getDownOps()) {
      if(op instanceof ParallelDo) {
        ParallelDo thisPDo = (ParallelDo)op;
        mPDo.addDest(thisPDo.getFunction(), thisPDo.getDest());
        LazyCollection thisDest = (LazyCollection)thisPDo.getDest();
        thisDest.deferredOp = mPDo;
      } else {
        newList.add(op);
      }
    }
    newList.add(mPDo);
    orig.downOps = newList;
    // Recursively apply this function to parent
    fuseSiblingParallelDos(orig);
  }
  
  /**
   * Fuse producer-consumer ParallelDos as in : {Orig2 => p2 => Orig1 => p1 => Output} to {Orig2 => p1(p2) => Output}
   * @param arg  The collection that may have compositions internally.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  <T> void fuseParallelDos(PCollection<T> arg) {
    LazyCollection<T> output = (LazyCollection<T>)arg;
    if(output.isMaterialized()) { // stop condition for recursive algorithm
      return;
    }
    DeferredOp dOp = output.getDeferredOp();
    if(!(dOp instanceof ParallelDo)) { // not a ParallelDo
      if(dOp instanceof OneToOneOp) {
        // Recursively apply this function to parent
        fuseParallelDos(((OneToOneOp)dOp).getOrigin());
        return;
      } 
      if(dOp instanceof Flatten) {
        Flatten<T> flatten = (Flatten)dOp;
        // Recursively apply this function to all parents
        for(PCollection<T> col: flatten.getOrigins()) {
          fuseParallelDos(col);
        }
        return;
      }
    }
    ParallelDo p1 = (ParallelDo)output.getDeferredOp();
    LazyCollection orig1 = (LazyCollection)p1.getOrigin();
    if(orig1.isMaterialized()) {
      return;
    }
    if(!(orig1.getDeferredOp() instanceof ParallelDo)) {
      // Recursively apply this function to parent node
      fuseParallelDos(orig1);
      return;
    }
    // At this point we know ParallelDo fusion can be done -> Perform it
    ParallelDo p2 = (ParallelDo)orig1.getDeferredOp();
    // Lift combine values
    if(p2 instanceof CombineValues) { 
      LazyCollection lCol = (LazyCollection)p2.getOrigin();
      if(!lCol.isMaterialized() && lCol.getDeferredOp() instanceof GroupByKey) {
        // Upper parallel do is CombineValues and follows a GroupByKey -> don't join
        fuseParallelDos(orig1);
        return;
      }
    }
    final DoFn f1 = p1.getFunction();
    final DoFn f2 = p2.getFunction();
    // Define the joined function
    DoFn newFn = new DoFn() {
      @Override
      public void process(Object v, final EmitFn emitter) {
        f2.process(v, new EmitFn() {
          @Override
          public void emit(Object v) {
            f1.process(v, emitter);
          }
        });
      }
    };
    LazyCollection orig2 = (LazyCollection)p2.getOrigin();
    ParallelDo newPDo = new ParallelDo(newFn, orig2, output);
    // Clean & change pointers
    orig2.downOps.remove(p2);
    orig1.downOps.remove(p1);
    orig2.addDownOp(newPDo);
    output.deferredOp = newPDo;
    // Recursively apply this function to the same node => TODO Beware infinite recursion, properly test
    fuseParallelDos(output);
  }
}

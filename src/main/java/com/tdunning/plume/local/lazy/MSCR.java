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

import java.util.HashSet;
import java.util.Set;

import com.tdunning.plume.PCollection;

/**
 * The MSCR abstraction as in FlumeJava paper
 * 
 * (work in progress)
 */
public class MSCR {

  Set<PCollection<?>> inputs = new HashSet<PCollection<?>>();
  Set<OutputChannel<?, ?, ?>> outputChannels;
  
  public Set<PCollection<?>> getInputs() {
    return inputs;
  }

  public <T> void addInput(PCollection<T> input) {
    inputs.add(input);
  }
  
  public <T> boolean hasInput(PCollection<T> input) {
    return inputs.contains(input);
  }
}

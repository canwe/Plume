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

package com.tdunning.plume.types;

import com.tdunning.plume.Pair;

/** Pair type. */
public class PairType<K,V> extends PType<Pair<K,V>> {
  private PType<K> keyType;
  private PType<V> valueType;

  public PairType(PType<K> keyType, PType<V> valueType) {
    super(Kind.PAIR);

    this.keyType = keyType;
    this.valueType = valueType;
  }

  public PType<K> keyType() { return keyType; }
  public PType<V> valueType() { return valueType; }
}
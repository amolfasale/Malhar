/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.lib.math;

import com.malhartech.annotation.InputPortFieldAnnotation;
import com.malhartech.api.DefaultInputPort;
import com.malhartech.api.DefaultOutputPort;

/**
 *
 * Adds all the values on "numerator" and "denominator" and emits quotient at end of window<p>
 * <br>
 * <b>Ports</b>:<br>
 * <b>numerator</b> expects V extends Number<br>
 * <b>denominator</b> expects V extends Number<br>
 * <b>quotient</b> emits Double<br>
 * <b>Specific compile time checks</b>: None<br>
 * <b>Specific run time checks</b>: None<br>
 * <p>
 * <b>Benchmarks</b>: Blast as many tuples as possible in inline mode<br>
 * <table border="1" cellspacing=1 cellpadding=1 summary="Benchmark table for QuotientValue&lt;V extends Number&gt; operator template">
 * <tr><th>In-Bound</th><th>Out-bound</th><th>Comments</th></tr>
 * <tr><td><b>&gt; 500 Million tuples/s</td><td>One Double tuple per window</td><td>In-bound rate is the main determinant of performance</td></tr>
 * </table><br>
 * <p>
 * <b>Function Table (V=Integer)</b>:
 * <table border="1" cellspacing=1 cellpadding=1 summary="Function table for QuotientValue&lt;V extends Number&gt; operator template">
 * <tr><th rowspan=2>Tuple Type (api)</th><th colspan=2>In-bound (process)</th><th>Out-bound (emit)</th></tr>
 * <tr><th><i>numerator</i>(&lt;V&gt;)</th><th><i>denominator</i>(&lt;V&gt;)</th><th><i>quotient</i>(&lt;V&gt;)</th></tr>
 * <tr><td>Begin Window (beginWindow())</td><td>N/A</td><td>N/A</td><td>N/A</td></tr>
 * <tr><td>Data (process())</td><td></td><td>28</td><td></td></tr>
 * <tr><td>Data (process())</td><td>2</td><td></td><td></td></tr>
 * <tr><td>Data (process())</td><td>1000</td><td></td><td></td></tr>
 * <tr><td>Data (process())</td><td>14</td><td></td><td></td></tr>
 * <tr><td>Data (process())</td><td>10</td><td></td><td></td></tr>
 * <tr><td>Data (process())</td><td>52</td><td></td><td></td></tr>
 * <tr><td>Data (process())</td><td>22</td><td></td><td></td></tr>
 * <tr><td>Data (process())</td><td>14</td><td></td><td></td></tr>
 * <tr><td>Data (process())</td><td>2</td><td></td><td></td></tr>
 * <tr><td>Data (process())</td><td></td><td>22</td><td></td></tr>
 * <tr><td>Data (process())</td><td>4</td><td></td><td></td></tr>
 * <tr><td>End Window (endWindow())</td><td>N/A</td><td>N/A</td><td>22.4</td></tr>
 * </table>
 * <br>
 *
 * @author Amol Kekre (amol@malhar-inc.com)<br>
 * <br>
 */
public class QuotientValue<V extends Number> extends BaseNumberValueOperator<V>
{
  @InputPortFieldAnnotation(name = "numerator")
  public final transient DefaultInputPort<V> numerator = new DefaultInputPort<V>(this)
  {
    /**
     * Adds to the numerator value
     */
    @Override
    public void process(V tuple)
    {
      nval += tuple.doubleValue();
    }
  };
  @InputPortFieldAnnotation(name = "denominator")
  public final transient DefaultInputPort<V> denominator = new DefaultInputPort<V>(this)
  {
    /**
     * Adds to the denominator value
     */
    @Override
    public void process(V tuple)
    {
      dval += tuple.doubleValue();
    }
  };
  @InputPortFieldAnnotation(name = "quotient")
  public final transient DefaultOutputPort<V> quotient = new DefaultOutputPort<V>(this);
  double nval = 0.0;
  double dval = 0.0;
  int mult_by = 1;

  public void setMult_by(int i)
  {
    mult_by = i;
  }

  /**
   * Clears denominator and numerator values
   *
   * @param windowId
   */
  @Override
  public void beginWindow(long windowId)
  {
    nval = 0.0;
    dval = 0.0;
  }

  /**
   * Generates tuple emits it as long as denomitor is not 0
   */
  @Override
  public void endWindow()
  {
    if (dval == 0) {
      return;
    }
    double val = (nval / dval) * mult_by;
    quotient.emit(getValue(val));
  }
}

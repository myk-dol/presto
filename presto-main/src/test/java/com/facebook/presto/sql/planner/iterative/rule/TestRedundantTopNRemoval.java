/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.spi.plan.AggregationNode;
import com.facebook.presto.spi.plan.TopNNode;
import com.facebook.presto.spi.plan.ValuesNode;
import com.facebook.presto.sql.planner.TestTableConstraintsConnectorFactory;
import com.facebook.presto.sql.planner.iterative.properties.LogicalPropertiesProviderImpl;
import com.facebook.presto.sql.planner.iterative.rule.test.BaseRuleTest;
import com.facebook.presto.sql.planner.iterative.rule.test.RuleTester;
import com.facebook.presto.sql.relational.FunctionResolution;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Optional;

import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.anyTree;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.node;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.output;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.tableScan;
import static java.util.Collections.emptyList;

public class TestRedundantTopNRemoval
        extends BaseRuleTest
{
    private LogicalPropertiesProviderImpl logicalPropertiesProvider;

    @BeforeClass
    public final void setUp()
    {
        tester = new RuleTester(emptyList(), ImmutableMap.of("exploit_constraints", Boolean.toString(true)), Optional.of(1), new TestTableConstraintsConnectorFactory(1));
        logicalPropertiesProvider = new LogicalPropertiesProviderImpl(new FunctionResolution(tester.getMetadata().getFunctionAndTypeManager().getFunctionAndTypeResolver()));
    }

    @Test
    public void singleTableTests()
    {
        tester().assertThat(ImmutableSet.of(new MergeLimitWithSort(), new RemoveRedundantTopN()), logicalPropertiesProvider)
                .on("SELECT totalprice FROM orders WHERE orderkey = 10 ORDER BY totalprice LIMIT 10")
                .validates(plan -> assertNodeRemovedFromPlan(plan, TopNNode.class));

        //single group and limit is 10
        tester().assertThat(new RemoveRedundantTopN(), logicalPropertiesProvider)
                .on(p ->
                        p.topN(
                                10,
                                ImmutableList.of(p.variable("c")),
                                p.aggregation(builder -> builder
                                        .source(p.values(p.variable("foo")))
                                        .addAggregation(p.variable("c"), p.rowExpression("count(foo)"))
                                        .globalGrouping())))
                .matches(
                        node(AggregationNode.class,
                                node(ValuesNode.class)));

        tester().assertThat(ImmutableSet.of(new MergeLimitWithSort(), new RemoveRedundantTopN()), logicalPropertiesProvider)
                .on("SELECT count(*) FROM orders ORDER BY 1 LIMIT 10")
                .validates(plan -> assertNodeRemovedFromPlan(plan, TopNNode.class));

        //negative tests
        tester().assertThat(ImmutableSet.of(new MergeLimitWithSort()), logicalPropertiesProvider)
                .on("SELECT totalprice FROM orders WHERE orderkey = 10 ORDER BY totalprice LIMIT 10")
                .validates(plan -> assertNodePresentInPlan(plan, TopNNode.class));

        tester().assertThat(ImmutableSet.of(new MergeLimitWithSort(), new RemoveRedundantTopN()), logicalPropertiesProvider)
                .on("SELECT orderkey, count(*) FROM orders GROUP BY orderkey ORDER BY 1 LIMIT 10")
                .matches(output(
                        node(TopNNode.class,
                                anyTree(
                                        tableScan("orders")))));
    }

    @Test
    public void complexQueryTests()
    {
        tester().assertThat(ImmutableSet.of(new MergeLimitWithSort(), new RemoveRedundantTopN()), logicalPropertiesProvider)
                .on("select totalprice from orders o inner join customer c on o.custkey = c.custkey where o.orderkey=10 order by totalprice limit 10")
                .validates(plan -> assertNodeRemovedFromPlan(plan, TopNNode.class));
        tester().assertThat(ImmutableSet.of(new MergeLimitWithSort(), new RemoveRedundantTopN()), logicalPropertiesProvider)
                .on("select a from orders join (values(2)) t(a) ON orderkey=1 order by orderkey limit 3")
                .validates(plan -> assertNodeRemovedFromPlan(plan, TopNNode.class));

        //negative test
        tester().assertThat(ImmutableSet.of(new MergeLimitWithSort()), logicalPropertiesProvider)
                .on("select totalprice from orders o inner join customer c on o.custkey = c.custkey where o.orderkey=10 order by totalprice limit 10")
                .validates(plan -> assertNodePresentInPlan(plan, TopNNode.class));
        tester().assertThat(ImmutableSet.of(new MergeLimitWithSort(), new RemoveRedundantTopN()), logicalPropertiesProvider)
                .on("select a from orders left join (values(2)) t(a) ON orderkey=1 order by orderkey limit 3")
                .validates(plan -> assertNodePresentInPlan(plan, TopNNode.class));
    }

    @Test
    public void doesNotFire()
    {
        tester().assertThat(new RemoveRedundantTopN(), logicalPropertiesProvider)
                .on(p ->
                        p.topN(
                                10,
                                ImmutableList.of(p.variable("c")),
                                p.aggregation(builder -> builder
                                        .source(p.values(20, p.variable("foo")))
                                        .addAggregation(p.variable("c"), p.rowExpression("count(foo)"))
                                        .singleGroupingSet(p.variable("foo")))))
                .doesNotFire();
    }

    @Test
    public void testFeatureDisabled()
    {
        // Disable the feature and verify that optimization rule is not applied.
        RuleTester newTester = new RuleTester(emptyList(), ImmutableMap.of("exploit_constraints", Boolean.toString(false)));

        newTester.assertThat(ImmutableSet.of(new MergeLimitWithSort(), new RemoveRedundantTopN()), logicalPropertiesProvider)
                .on("SELECT totalprice FROM orders WHERE orderkey = 10 ORDER BY totalprice LIMIT 10")
                .validates(plan -> assertNodePresentInPlan(plan, TopNNode.class));
    }
}

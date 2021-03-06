/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Assert;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Fuseable;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.subscriber.AssertSubscriber;
import reactor.util.concurrent.QueueSupplier;

import static org.assertj.core.api.Assertions.assertThat;

public class FluxGroupByTest extends AbstractFluxOperatorTest<String, GroupedFlux<Integer, String>>{

	@Override
	protected Scenario<String, GroupedFlux<Integer, String>> defaultScenarioOptions(Scenario<String, GroupedFlux<Integer, String>> defaultOptions) {
		return defaultOptions.fusionMode(Fuseable.ASYNC)
		                     .prefetch(QueueSupplier.SMALL_BUFFER_SIZE)
		                     .shouldAssertPostTerminateState(false);
	}

	@Override
	protected int fusionModeThreadBarrierSupport() {
		return Fuseable.ANY;
	}

	@Override
	protected List<Scenario<String, GroupedFlux<Integer, String>>> scenarios_errorFromUpstreamFailure() {
		return Arrays.asList(
				scenario(f -> f.groupBy(String::hashCode))
		);
	}

	@Override
	protected List<Scenario<String, GroupedFlux<Integer, String>>> scenarios_threeNextAndComplete() {
		return Arrays.asList(
				scenario(f -> f.groupBy(String::hashCode))
					.verifier(step ->
							step.assertNext(g -> assertThat(g.key()).isEqualTo(g.blockFirst().hashCode()))
							    .assertNext(g -> assertThat(g.key()).isEqualTo(g.blockFirst().hashCode()))
							    .assertNext(g -> assertThat(g.key()).isEqualTo(g.blockFirst().hashCode()))
							    .verifyComplete())
		);
	}

	@Override
	protected List<Scenario<String, GroupedFlux<Integer, String>>> scenarios_errorInOperatorCallback() {
		return Arrays.asList(
				scenario(f -> f.groupBy(String::hashCode, s -> {
					throw exception();
				})),

				scenario(f -> f.groupBy(s -> {
					throw exception();
				})),

				scenario(f -> f.groupBy(String::hashCode, s -> null))
						.verifier(step -> step.verifyError(NullPointerException.class)),

				scenario(f -> f.groupBy(k -> null))
						.verifier(step -> step.verifyError(NullPointerException.class))
		);
	}

	@Test
	public void normal() {
		AssertSubscriber<GroupedFlux<Integer, Integer>> ts = AssertSubscriber.create();

		Flux.range(1, 10)
		    .groupBy(k -> k % 2)
		    .subscribe(ts);

		ts.assertValueCount(2)
		  .assertNoError()
		  .assertComplete();

		AssertSubscriber<Integer> ts1 = AssertSubscriber.create();
		ts.values()
		  .get(0)
		  .subscribe(ts1);
		ts1.assertValues(1, 3, 5, 7, 9);

		AssertSubscriber<Integer> ts2 = AssertSubscriber.create();
		ts.values()
		  .get(1)
		  .subscribe(ts2);
		ts2.assertValues(2, 4, 6, 8, 10);
	}

	@Test
	public void normalValueSelector() {
		AssertSubscriber<GroupedFlux<Integer, Integer>> ts = AssertSubscriber.create();

		Flux.range(1, 10)
		    .groupBy(k -> k % 2, v -> -v)
		    .subscribe(ts);

		ts.assertValueCount(2)
		  .assertNoError()
		  .assertComplete();

		AssertSubscriber<Integer> ts1 = AssertSubscriber.create();
		ts.values()
		  .get(0)
		  .subscribe(ts1);
		ts1.assertValues(-1, -3, -5, -7, -9);

		AssertSubscriber<Integer> ts2 = AssertSubscriber.create();
		ts.values()
		  .get(1)
		  .subscribe(ts2);
		ts2.assertValues(-2, -4, -6, -8, -10);
	}

	@Test
	public void takeTwoGroupsOnly() {
		AssertSubscriber<GroupedFlux<Integer, Integer>> ts = AssertSubscriber.create();

		Flux.range(1, 10)
		    .groupBy(k -> k % 3)
		    .take(2)
		    .subscribe(ts);

		ts.assertValueCount(2)
		  .assertNoError()
		  .assertComplete();

		AssertSubscriber<Integer> ts1 = AssertSubscriber.create();
		ts.values()
		  .get(0)
		  .subscribe(ts1);
		ts1.assertValues(1, 4, 7, 10);

		AssertSubscriber<Integer> ts2 = AssertSubscriber.create();
		ts.values()
		  .get(1)
		  .subscribe(ts2);
		ts2.assertValues(2, 5, 8);
	}

	@Test
	public void keySelectorNull() {
		AssertSubscriber<GroupedFlux<Integer, Integer>> ts = AssertSubscriber.create();

		Flux.range(1, 10)
		    .groupBy(k -> (Integer) null)
		    .subscribe(ts);

		ts.assertError(NullPointerException.class);
	}

	@Test
	public void valueSelectorNull() {
		AssertSubscriber<GroupedFlux<Integer, Integer>> ts = AssertSubscriber.create();

		Flux.range(1, 10)
		    .groupBy(k -> 1, v -> (Integer) null)
		    .subscribe(ts);

		ts.assertError(NullPointerException.class);
	}

	@Test
	public void error() {
		AssertSubscriber<GroupedFlux<Integer, Integer>> ts = AssertSubscriber.create();

		Flux.<Integer>error(new RuntimeException("forced failure")).groupBy(k -> k)
		                                                           .subscribe(ts);

		ts.assertErrorMessage("forced failure");
	}

	@Test
	public void backpressure() {
		AssertSubscriber<GroupedFlux<Integer, Integer>> ts = AssertSubscriber.create(0L);

		Flux.range(1, 10)
		    .groupBy(k -> 1)
		    .subscribe(ts);

		ts.assertNoEvents();

		ts.request(1);

		ts.assertValueCount(1)
		  .assertNoError()
		  .assertComplete();

		AssertSubscriber<Integer> ts1 = AssertSubscriber.create(0L);

		ts.values()
		  .get(0)
		  .subscribe(ts1);

		ts1.assertNoEvents();

		ts1.request(10);

		ts1.assertValues(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
	}

	@Test
	public void flatMapBack() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		Flux.range(1, 10)
		    .groupBy(k -> k % 2)
		    .flatMap(g -> g)
		    .subscribe(ts);

		ts.assertValues(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
	}

	@Test
	public void flatMapBackHidden() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		Flux.range(1, 10)
		    .groupBy(k -> k % 2)
		    .flatMap(g -> g.hide())
		    .subscribe(ts);

		ts.assertValues(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
	}

	@Test
	public void concatMapBack() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		Flux.range(1, 10)
		    .groupBy(k -> k % 2)
		    .concatMap(g -> g)
		    .subscribe(ts);

		ts.assertValues(1, 3, 5, 7, 9, 2, 4, 6, 8, 10);
	}

	@Test
	public void concatMapBackHidden() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		Flux.range(1, 10)
		    .groupBy(k -> k % 2)
		    .hide()
		    .concatMap(g -> g)
		    .subscribe(ts);

		ts.assertValues(1, 3, 5, 7, 9, 2, 4, 6, 8, 10);
	}

	@Test
	public void empty() {
		AssertSubscriber<GroupedFlux<Integer, Integer>> ts = AssertSubscriber.create(0L);

		Flux.<Integer>empty().groupBy(v -> v)
		                     .subscribe(ts);

		ts.assertValues();
	}

	@Test
	public void oneGroupLongMerge() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		Flux.range(1, 1_000_000)
		    .groupBy(v -> 1)
		    .flatMap(g -> g)
		    .subscribe(ts);

		ts.assertValueCount(1_000_000)
		  .assertNoError()
		  .assertComplete();
	}

	@Test
	public void oneGroupLongMergeHidden() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		Flux.range(1, 1_000_000)
		    .groupBy(v -> 1)
		    .flatMap(g -> g.hide())
		    .subscribe(ts);

		ts.assertValueCount(1_000_000)
		  .assertNoError()
		  .assertComplete();
	}

	@Test
	public void twoGroupsLongMerge() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		Flux.range(1, 1_000_000)
		    .groupBy(v -> (v & 1))
		    .flatMap(g -> g)
		    .subscribe(ts);

		ts.assertValueCount(1_000_000)
		  .assertNoError()
		  .assertComplete();
	}

	@Test
	public void twoGroupsLongMergeHidden() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		Flux.range(1, 1_000_000)
		    .groupBy(v -> (v & 1))
		    .flatMap(g -> g.hide())
		    .subscribe(ts);

		ts.assertValueCount(1_000_000)
		  .assertNoError()
		  .assertComplete();
	}

	@Test
	public void twoGroupsLongAsyncMerge() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		Flux.range(1, 1_000_000)
		    .groupBy(v -> (v & 1))
		    .flatMap(g -> g)
		    .publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()))
		    .subscribe(ts);

		ts.await(Duration.ofSeconds(5));

		ts.assertValueCount(1_000_000)
		  .assertNoError()
		  .assertComplete();
	}

	@Test
	public void twoGroupsLongAsyncMergeHidden() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		Flux.range(1, 1_000_000)
		    .groupBy(v -> (v & 1))
		    .flatMap(g -> g.hide())
		    .publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()))
		    .subscribe(ts);

		ts.await(Duration.ofSeconds(5));

		ts.assertValueCount(1_000_000)
		  .assertNoError()
		  .assertComplete();
	}

	@Test
	public void twoGroupsConsumeWithSubscribe() {

		AssertSubscriber<Integer> ts1 = AssertSubscriber.create();
		AssertSubscriber<Integer> ts2 = AssertSubscriber.create();
		AssertSubscriber<Integer> ts3 = AssertSubscriber.create();
		ts3.onSubscribe(Operators.emptySubscription());

		Flux.range(0, 1_000_000)
		    .groupBy(v -> v & 1)
		    .subscribe(new Subscriber<GroupedFlux<Integer, Integer>>() {
			    @Override
			    public void onSubscribe(Subscription s) {
				    s.request(Long.MAX_VALUE);
			    }

			    @Override
			    public void onNext(GroupedFlux<Integer, Integer> t) {
				    if (t.key() == 0) {
					    t.publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()))
					     .subscribe(ts1);
				    }
				    else {
					    t.publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()))
					     .subscribe(ts2);
				    }
			    }

			    @Override
			    public void onError(Throwable t) {
				    ts3.onError(t);
			    }

			    @Override
			    public void onComplete() {
				    ts3.onComplete();
			    }
		    });

		ts1.await(Duration.ofSeconds(5));
		ts2.await(Duration.ofSeconds(5));
		ts3.await(Duration.ofSeconds(5));

		ts1.assertValueCount(500_000)
		   .assertNoError()
		   .assertComplete();

		ts2.assertValueCount(500_000)
		   .assertNoError()
		   .assertComplete();

		ts3.assertNoValues()
		   .assertNoError()
		   .assertComplete();

	}

	@Test
	public void twoGroupsConsumeWithSubscribePrefetchSmaller() {

		AssertSubscriber<Integer> ts1 = AssertSubscriber.create();
		AssertSubscriber<Integer> ts2 = AssertSubscriber.create();
		AssertSubscriber<Integer> ts3 = AssertSubscriber.create();
		ts3.onSubscribe(Operators.emptySubscription());

		Flux.range(0, 1_000_000)
		    .groupBy(v -> v & 1)
		    .subscribe(new Subscriber<GroupedFlux<Integer, Integer>>() {
			    @Override
			    public void onSubscribe(Subscription s) {
				    s.request(Long.MAX_VALUE);
			    }

			    @Override
			    public void onNext(GroupedFlux<Integer, Integer> t) {
				    if (t.key() == 0) {
					    t.publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()),
							    32)
					     .subscribe(ts1);
				    }
				    else {
					    t.publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()),
							    32)
					     .subscribe(ts2);
				    }
			    }

			    @Override
			    public void onError(Throwable t) {
				    ts3.onError(t);
			    }

			    @Override
			    public void onComplete() {
				    ts3.onComplete();
			    }
		    });

		if (!ts1.await(Duration.ofSeconds(5))
		        .isTerminated()) {
			Assert.fail("main subscriber timed out");
		}
		if (!ts2.await(Duration.ofSeconds(5))
		        .isTerminated()) {
			Assert.fail("group 0 subscriber timed out");
		}
		if (!ts3.await(Duration.ofSeconds(5))
		        .isTerminated()) {
			Assert.fail("group 1 subscriber timed out");
		}

		ts1.assertValueCount(500_000)
		   .assertNoError()
		   .assertComplete();

		ts2.assertValueCount(500_000)
		   .assertNoError()
		   .assertComplete();

		ts3.assertNoValues()
		   .assertNoError()
		   .assertComplete();

	}

	@Test
	public void twoGroupsConsumeWithSubscribePrefetchBigger() {

		AssertSubscriber<Integer> ts1 = AssertSubscriber.create();
		AssertSubscriber<Integer> ts2 = AssertSubscriber.create();
		AssertSubscriber<Integer> ts3 = AssertSubscriber.create();
		ts3.onSubscribe(Operators.emptySubscription());

		Flux.range(0, 1_000_000)
		    .groupBy(v -> v & 1)
		    .subscribe(new Subscriber<GroupedFlux<Integer, Integer>>() {
			    @Override
			    public void onSubscribe(Subscription s) {
				    s.request(Long.MAX_VALUE);
			    }

			    @Override
			    public void onNext(GroupedFlux<Integer, Integer> t) {
				    if (t.key() == 0) {
					    t.publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()),
							    1024)
					     .subscribe(ts1);
				    }
				    else {
					    t.publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()),
							    1024)
					     .subscribe(ts2);
				    }
			    }

			    @Override
			    public void onError(Throwable t) {
				    ts3.onError(t);
			    }

			    @Override
			    public void onComplete() {
				    ts3.onComplete();
			    }
		    });

		if (!ts1.await(Duration.ofSeconds(5))
		        .isTerminated()) {
			Assert.fail("main subscriber timed out");
		}
		if (!ts2.await(Duration.ofSeconds(5))
		        .isTerminated()) {
			Assert.fail("group 0 subscriber timed out");
		}
		if (!ts3.await(Duration.ofSeconds(5))
		        .isTerminated()) {
			Assert.fail("group 1 subscriber timed out");
		}

		ts1.assertValueCount(500_000)
		   .assertNoError()
		   .assertComplete();

		ts2.assertValueCount(500_000)
		   .assertNoError()
		   .assertComplete();

		ts3.assertNoValues()
		   .assertNoError()
		   .assertComplete();

	}

	@Test
	public void twoGroupsConsumeWithSubscribeHide() {

		AssertSubscriber<Integer> ts1 = AssertSubscriber.create();
		AssertSubscriber<Integer> ts2 = AssertSubscriber.create();
		AssertSubscriber<Integer> ts3 = AssertSubscriber.create();
		ts3.onSubscribe(Operators.emptySubscription());

		Flux.range(0, 1_000_000)
		    .groupBy(v -> v & 1)
		    .subscribe(new Subscriber<GroupedFlux<Integer, Integer>>() {
			    @Override
			    public void onSubscribe(Subscription s) {
				    s.request(Long.MAX_VALUE);
			    }

			    @Override
			    public void onNext(GroupedFlux<Integer, Integer> t) {
				    if (t.key() == 0) {
					    t.hide()
					     .publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()))
					     .subscribe(ts1);
				    }
				    else {
					    t.hide()
					     .publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()))
					     .subscribe(ts2);
				    }
			    }

			    @Override
			    public void onError(Throwable t) {
				    ts3.onError(t);
			    }

			    @Override
			    public void onComplete() {
				    ts3.onComplete();
			    }
		    });

		ts1.await(Duration.ofSeconds(5));
		ts2.await(Duration.ofSeconds(5));
		ts3.await(Duration.ofSeconds(5));

		ts1.assertValueCount(500_000)
		   .assertNoError()
		   .assertComplete();

		ts2.assertValueCount(500_000)
		   .assertNoError()
		   .assertComplete();

		ts3.assertNoValues()
		   .assertNoError()
		   .assertComplete();

	}

	@Test
	public void twoGroupsFullAsyncFullHide() {

		AssertSubscriber<Integer> ts1 = AssertSubscriber.create();
		AssertSubscriber<Integer> ts2 = AssertSubscriber.create();
		AssertSubscriber<Integer> ts3 = AssertSubscriber.create();
		ts3.onSubscribe(Operators.emptySubscription());

		Flux.range(0, 1_000_000)
		    .hide()
		    .publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()))
		    .groupBy(v -> v & 1)
		    .subscribe(new Subscriber<GroupedFlux<Integer, Integer>>() {
			    @Override
			    public void onSubscribe(Subscription s) {
				    s.request(Long.MAX_VALUE);
			    }

			    @Override
			    public void onNext(GroupedFlux<Integer, Integer> t) {
				    if (t.key() == 0) {
					    t.hide()
					     .publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()))
					     .subscribe(ts1);
				    }
				    else {
					    t.hide()
					     .publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()))
					     .subscribe(ts2);
				    }
			    }

			    @Override
			    public void onError(Throwable t) {
				    ts3.onError(t);
			    }

			    @Override
			    public void onComplete() {
				    ts3.onComplete();
			    }
		    });

		ts1.await(Duration.ofSeconds(5));
		ts2.await(Duration.ofSeconds(5));
		ts3.await(Duration.ofSeconds(5));

		ts1.assertValueCount(500_000)
		   .assertNoError()
		   .assertComplete();

		ts2.assertValueCount(500_000)
		   .assertNoError()
		   .assertComplete();

		ts3.assertNoValues()
		   .assertNoError()
		   .assertComplete();

	}

	@Test
	public void twoGroupsFullAsync() {

		AssertSubscriber<Integer> ts1 = AssertSubscriber.create();
		AssertSubscriber<Integer> ts2 = AssertSubscriber.create();
		AssertSubscriber<Integer> ts3 = AssertSubscriber.create();
		ts3.onSubscribe(Operators.emptySubscription());

		Flux.range(0, 1_000_000)
		    .publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()), 512)
		    .groupBy(v -> v & 1)
		    .subscribe(new Subscriber<GroupedFlux<Integer, Integer>>() {
			    @Override
			    public void onSubscribe(Subscription s) {
				    s.request(Long.MAX_VALUE);
			    }

			    @Override
			    public void onNext(GroupedFlux<Integer, Integer> t) {
				    if (t.key() == 0) {
					    t.publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()))
					     .subscribe(ts1);
				    }
				    else {
					    t.publishOn(Schedulers.fromExecutorService(ForkJoinPool.commonPool()))
					     .subscribe(ts2);
				    }
			    }

			    @Override
			    public void onError(Throwable t) {
				    ts3.onError(t);
			    }

			    @Override
			    public void onComplete() {
				    ts3.onComplete();
			    }
		    });

		ts1.await(Duration.ofSeconds(5));
		ts2.await(Duration.ofSeconds(5));
		ts3.await(Duration.ofSeconds(5));

		ts1.assertValueCount(500_000)
		   .assertNoError()
		   .assertComplete();

		ts2.assertValueCount(500_000)
		   .assertNoError()
		   .assertComplete();

		ts3.assertNoValues()
		   .assertNoError()
		   .assertComplete();

	}

	@Test
	public void groupsCompleteAsSoonAsMainCompletes() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		Flux.range(0, 20)
		    .groupBy(i -> i % 5)
		    .concatMap(v -> v, 2)
		    .subscribe(ts);

		ts.assertValues(0,
				5,
				10,
				15,
				1,
				6,
				11,
				16,
				2,
				7,
				12,
				17,
				3,
				8,
				13,
				18,
				4,
				9,
				14,
				19)
		  .assertComplete()
		  .assertNoError();
	}

	@Test
	public void groupsCompleteAsSoonAsMainCompletesNoFusion() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		Flux.range(0, 20)
		    .groupBy(i -> i % 5)
		    .hide()
		    .concatMap(v -> v, 2)
		    .subscribe(ts);

		ts.assertValues(0,
				5,
				10,
				15,
				1,
				6,
				11,
				16,
				2,
				7,
				12,
				17,
				3,
				8,
				13,
				18,
				4,
				9,
				14,
				19)
		  .assertComplete()
		  .assertNoError();
	}

	@Test
	public void prefetchIsUsed() {
		AtomicLong initialRequest = new AtomicLong();

		StepVerifier.create(Flux.range(1, 10)
		                        .doOnRequest(r -> initialRequest.compareAndSet(0L, r))
		                        .groupBy(i -> i % 5, 11)
		                        .concatMap(v -> v))
		            .expectNextCount(10)
		            .verifyComplete();

		assertThat(initialRequest.get()).isEqualTo(11);
	}

	@Test
	public void prefetchMaxRequestsUnbounded() {
		AtomicLong initialRequest = new AtomicLong();

		StepVerifier.create(Flux.range(1, 10)
		                        .doOnRequest(r -> initialRequest.compareAndSet(0L, r))
		                        .groupBy(i -> i % 5, Integer.MAX_VALUE)
		                        .concatMap(v -> v))
		            .expectNextCount(10)
		            .verifyComplete();

		assertThat(initialRequest.get()).isEqualTo(Long.MAX_VALUE);
	}

}

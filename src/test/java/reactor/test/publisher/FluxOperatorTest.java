/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.test.publisher;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.junit.Assert;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.Fuseable;
import reactor.core.Loopback;
import reactor.core.MultiProducer;
import reactor.core.MultiReceiver;
import reactor.core.Producer;
import reactor.core.Receiver;
import reactor.core.Trackable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.core.publisher.ReplayProcessor;
import reactor.core.publisher.UnicastProcessor;
import reactor.test.StepVerifier;
import reactor.util.concurrent.QueueSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.core.Fuseable.*;
import static reactor.core.Trackable.UNSPECIFIED;

public abstract class FluxOperatorTest<I, O>
		extends BaseOperatorTest<I, Flux<I>, O, Flux<O>> {

	public final Scenario<I, O> scenario(Function<Flux<I>, ? extends Flux<O>> scenario) {
		if (defaultEmpty) {
			return Scenario.create(scenario)
			               .applyAllOptions(defaultScenario.duplicate()
			                                               .receiverEmpty());
		}
		return Scenario.create(scenario)
		               .applyAllOptions(defaultScenario);
	}

	static public final class Scenario<I, O>
			extends OperatorScenario<I, Flux<I>, O, Flux<O>> {

		static <I, O> Scenario<I, O> create(Function<Flux<I>, ? extends Flux<O>> scenario) {
			return new Scenario<>(scenario, new Exception("scenario:"));
		}

		Scenario(Function<Flux<I>, ? extends Flux<O>> scenario, Exception stack) {
			super(scenario, stack);
		}

		@Override
		Scenario<I, O> duplicate() {
			return new Scenario<>(scenario, stack).applyAllOptions(this);
		}

		@Override
		public Scenario<I, O> shouldHitDropNextHookAfterTerminate(boolean shouldHitDropNextHookAfterTerminate) {
			super.shouldHitDropNextHookAfterTerminate(shouldHitDropNextHookAfterTerminate);
			return this;
		}

		@Override
		public Scenario<I, O> shouldHitDropErrorHookAfterTerminate(boolean shouldHitDropErrorHookAfterTerminate) {
			super.shouldHitDropErrorHookAfterTerminate(
					shouldHitDropErrorHookAfterTerminate);
			return this;
		}

		@Override
		public Scenario<I, O> shouldAssertPostTerminateState(boolean shouldAssertPostTerminateState) {
			super.shouldAssertPostTerminateState(shouldAssertPostTerminateState);
			return this;
		}

		@Override
		public Scenario<I, O> fusionMode(int fusionMode) {
			super.fusionMode(fusionMode);
			return this;
		}

		@Override
		public Scenario<I, O> fusionModeThreadBarrier(int fusionModeThreadBarrier) {
			super.fusionModeThreadBarrier(fusionModeThreadBarrier);
			return this;
		}

		@Override
		public Scenario<I, O> prefetch(int prefetch) {
			super.prefetch(prefetch);
			return this;
		}

		@Override
		public Scenario<I, O> producerEmpty() {
			super.producerEmpty();
			return this;
		}

		@Override
		public Scenario<I, O> producerNever() {
			super.producerNever();
			return this;
		}

		@Override
		public Scenario<I, O> producer(int n, IntFunction<? extends I> producer) {
			super.producer(n, producer);
			return this;
		}

		@Override
		public final Scenario<I, O> receiverEmpty() {
			super.receiverEmpty();
			return this;
		}

		@Override
		@SafeVarargs
		public final Scenario<I, O> receive(Consumer<? super O>... receivers) {
			super.receive(receivers);
			return this;
		}

		@Override
		@SafeVarargs
		public final Scenario<I, O> receiveValues(O... receivers) {
			super.receiveValues(receivers);
			return this;
		}

		@Override
		public final Scenario<I, O> receive(int i, IntFunction<? extends O> receivers) {
			super.receive(i, receivers);
			return this;
		}

		@Override
		public Scenario<I, O> receiverDemand(long d) {
			super.receiverDemand(d);
			return this;
		}

		@Override
		public Scenario<I, O> description(String description) {
			super.description(description);
			return this;
		}

		@Override
		public Scenario<I, O> verifier(Consumer<StepVerifier.Step<O>> verifier) {
			super.verifier(verifier);
			return this;
		}

		@Override
		public Scenario<I, O> applyAllOptions(OperatorScenario<I, Flux<I>, O, Flux<O>> source) {
			super.applyAllOptions(source);
			return this;
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public final void assertPrePostState() {
		forEachScenario(scenarios_touchAndAssertState(), scenario -> {

			Flux<O> f = Flux.<I>from(s -> {
				Trackable t = null;
				if (s instanceof Trackable) {
					t = (Trackable) s;
					assertThat(t.getError()).isNull();
					assertThat(t.isStarted()).isFalse();
					assertThat(t.isTerminated()).isFalse();
					assertThat(t.isCancelled()).isFalse();

					if (scenario.prefetch() != UNSPECIFIED) {
						assertThat(Math.min(Integer.MAX_VALUE,
								t.getCapacity())).isEqualTo(scenario.prefetch());
						if (t.expectedFromUpstream() != UNSPECIFIED && t.expectedFromUpstream() != t.limit()) {
							assertThat(t.expectedFromUpstream()).isEqualTo(0);
						}
						if (t.limit() != UNSPECIFIED) {
							assertThat(t.limit()).isEqualTo(defaultLimit(scenario));
						}
					}

					if (t.getPending() != UNSPECIFIED && scenario.shouldAssertPostTerminateState()) {
						assertThat(t.getPending()).isEqualTo(3);
					}
					if (t.requestedFromDownstream() != UNSPECIFIED && scenario.shouldAssertPostTerminateState()) {
						assertThat(t.requestedFromDownstream()).isEqualTo(0);
					}
				}

				if (s instanceof Receiver) {
					assertThat(((Receiver) s).upstream()).isNull();
				}

				touchTreeState(s);

				s.onSubscribe(Operators.emptySubscription());
				s.onSubscribe(Operators.emptySubscription()); //noop path
				s.onSubscribe(Operators.cancelledSubscription()); //noop path
				if (t != null) {

					assertThat(t.isStarted()).isTrue();
					if (scenario.prefetch() != UNSPECIFIED) {
						if (t.expectedFromUpstream() != UNSPECIFIED && t.expectedFromUpstream() != t.limit()) {
							assertThat(t.expectedFromUpstream()).isEqualTo(scenario.prefetch());
						}
					}
					if (t.requestedFromDownstream() != UNSPECIFIED) {
						assertThat(t.requestedFromDownstream()).isEqualTo(Long.MAX_VALUE);
					}
				}
				s.onComplete();
				if (t != null) {
					touchTreeState(s);
					if (scenario.shouldAssertPostTerminateState()) {
						assertThat(t.isStarted()).isFalse();
						assertThat(t.isTerminated()).isTrue();
					}
				}
			}).as(scenario.body());

			if (scenario.prefetch() != UNSPECIFIED) {
				assertThat(Math.min(f.getPrefetch(), Integer.MAX_VALUE)).isEqualTo(
						scenario.prefetch());
			}

			if (f instanceof Loopback) {
				assertThat(((Loopback) f).connectedInput()).isNotNull();
			}

			f.subscribe();

			f = f.filter(t -> true);

			if (scenario.prefetch() != UNSPECIFIED) {
				assertThat(Math.min(((Flux) (((Receiver) f).upstream())).getPrefetch(),
						Integer.MAX_VALUE)).isEqualTo(scenario.prefetch());
			}

			if (((Receiver) f).upstream() instanceof Loopback) {
				assertThat(((Loopback) (((Receiver) f).upstream())).connectedInput()).isNotNull();
			}

			f.subscribe();

			AtomicReference<Trackable> ref = new AtomicReference<>();
			Flux<O> source = finiteSourceSync(scenario).doOnSubscribe(s -> {
				if (s instanceof Producer) {
					Object _s = ((Producer) ((Producer) s).downstream()).downstream();
					if (_s instanceof Trackable) {
						Trackable t = (Trackable) _s;
						ref.set(t);
						assertThat(t.isStarted()).isFalse();
						assertThat(t.isCancelled()).isFalse();
						if (scenario.prefetch() != UNSPECIFIED) {
							assertThat(Math.min(Integer.MAX_VALUE,
									t.getCapacity())).isEqualTo(scenario.prefetch());
							if (t.expectedFromUpstream() != UNSPECIFIED && t.limit() != t.expectedFromUpstream()) {
								assertThat(t.expectedFromUpstream()).isEqualTo(0);
							}
							if (t.limit() != UNSPECIFIED) {
								assertThat(t.limit()).isEqualTo(defaultLimit(scenario));
							}
						}
						if (t.getPending() != UNSPECIFIED && scenario.shouldAssertPostTerminateState()) {
							assertThat(t.getPending()).isEqualTo(3);
						}
						if (t.requestedFromDownstream() != UNSPECIFIED && scenario.shouldAssertPostTerminateState()) {
							assertThat(t.requestedFromDownstream()).isEqualTo(0);
						}
						if (t.expectedFromUpstream() != UNSPECIFIED && scenario.shouldAssertPostTerminateState() && t.limit() != t.expectedFromUpstream()) {
							assertThat(t.expectedFromUpstream()).isEqualTo(0);
						}
					}
				}
			})
			                                           .as(scenario.body());

			if (source.getPrefetch() != UNSPECIFIED && scenario.prefetch() != UNSPECIFIED) {
				assertThat(Math.min(source.getPrefetch(), Integer.MAX_VALUE)).isEqualTo(
						scenario.prefetch());
			}

			f = source.doOnSubscribe(parent -> {
				if (parent instanceof Trackable) {
					Trackable t = (Trackable) parent;
					assertThat(t.getError()).isNull();
					assertThat(t.isStarted()).isTrue();
					if (scenario.prefetch() != UNSPECIFIED && t.expectedFromUpstream() != UNSPECIFIED) {
						assertThat(t.expectedFromUpstream()).isEqualTo(scenario.prefetch());
					}
					assertThat(t.isTerminated()).isFalse();
				}

				//noop path
				if (parent instanceof Subscriber) {
					((Subscriber<I>) parent).onSubscribe(Operators.emptySubscription());
					((Subscriber<I>) parent).onSubscribe(Operators.cancelledSubscription());
				}

				if (parent instanceof Receiver) {
					assertThat(((Receiver) parent).upstream()).isNotNull();
				}

				touchTreeState(parent);
			})
			          .doOnComplete(() -> {
				          if (ref.get() != null) {
					          Trackable t = ref.get();
					          if (t.requestedFromDownstream() != UNSPECIFIED) {
						          assertThat(t.requestedFromDownstream()).isEqualTo(Long.MAX_VALUE);
					          }
					          if (scenario.shouldAssertPostTerminateState()) {
						          assertThat(t.isStarted()).isFalse();
						          assertThat(t.isTerminated()).isTrue();
					          }
					          touchTreeState(ref.get());
				          }
			          });

			f.subscribe(r -> touchTreeState(ref.get()));

			source = source.filter(t -> true);

			if (scenario.prefetch() != UNSPECIFIED) {
				assertThat(Math.min(((Flux) (((Receiver) source).upstream())).getPrefetch(),
						Integer.MAX_VALUE)).isEqualTo(scenario.prefetch());
			}

			f = f.filter(t -> true);

			f.subscribe(r -> touchTreeState(ref.get()));
		});
	}

	@Test
	public final void sequenceOfNextWithCallbackError() {
		defaultEmpty = true;
		forEachScenario(scenarios_operatorError(), scenario -> {
			Consumer<StepVerifier.Step<O>> verifier = scenario.verifier();

			String m = exception().getMessage();
			Consumer<StepVerifier.Step<O>> errorVerifier = step -> {
				try {
					step.consumeErrorWith(e -> {
						if (e instanceof NullPointerException || e instanceof IllegalStateException || e.getMessage()
						                                                                                .equals(m)) {
							return;
						}
						throw Exceptions.propagate(e);
					}).verify();
//						step.expectErrorMessage(m)
//						.verifyThenAssertThat()
//						.hasOperatorErrorWithMessage(m);
				}
				catch (Throwable e) {
					if (e instanceof AssertionError) {
						throw (AssertionError) e;
					}
					e = Exceptions.unwrap(e);
					if (e instanceof NullPointerException || e instanceof IllegalStateException || e.getMessage()
					                                                                                .equals(m)) {
						return;
					}
					throw Exceptions.propagate(e);
				}
			};

			if (verifier == null) {
				verifier = step -> errorVerifier.accept(scenario.applySteps(step));
				errorVerifier.accept(this.operatorNextVerifierBackpressured(scenario));
			}
			else {
				verifier.accept(this.operatorNextVerifierBackpressured(scenario));
			}

			int fusion = scenario.fusionMode();

			verifier.accept(this.operatorNextVerifier(scenario));
			verifier.accept(this.operatorNextVerifierFused(scenario));

			if (scenario.producerCount() > 0 && (fusion & Fuseable.SYNC) != 0) {
				verifier.accept(this.operatorNextVerifierFusedSync(scenario));
				verifier.accept(this.operatorNextVerifierFusedConditionalSync(scenario));
			}

			if (scenario.producerCount() > 0 && (fusion & Fuseable.ASYNC) != 0) {
				verifier.accept(this.operatorNextVerifierFusedAsync(scenario));
				verifier.accept(this.operatorNextVerifierFusedConditionalAsync(scenario));
				this.operatorNextVerifierFusedAsyncState(scenario);
				this.operatorNextVerifierFusedConditionalAsyncState(scenario);
			}

			verifier.accept(this.operatorNextVerifierTryNext(scenario));
			verifier.accept(this.operatorNextVerifierBothConditional(scenario));
			verifier.accept(this.operatorNextVerifierConditionalTryNext(scenario));
			verifier.accept(this.operatorNextVerifierFusedTryNext(scenario));
			verifier.accept(this.operatorNextVerifierFusedBothConditional(scenario));
			verifier.accept(this.operatorNextVerifierFusedBothConditionalTryNext(scenario));
		});
	}

	@Test
	public final void errorOnSubscribe() {
		defaultEmpty = true;
		forEachScenario(scenarios_errorFromUpstreamFailure(), scenario -> {
			Consumer<StepVerifier.Step<O>> verifier = scenario.verifier();

			if (verifier == null) {
				String m = exception().getMessage();
				verifier = step -> {
					try {
						scenario.applySteps(step)
						        .verifyErrorMessage(m);
					}
					catch (Exception e) {
						assertThat(Exceptions.unwrap(e)).hasMessage(m);
					}
				};
			}

			int fusion = scenario.fusionMode();

			verifier.accept(this.operatorErrorSourceVerifier(scenario));
			verifier.accept(this.operatorErrorSourceVerifierTryNext(scenario));
			verifier.accept(this.operatorErrorSourceVerifierFused(scenario));
			verifier.accept(this.operatorErrorSourceVerifierConditional(scenario));
			verifier.accept(this.operatorErrorSourceVerifierConditionalTryNext(scenario));
			verifier.accept(this.operatorErrorSourceVerifierFusedBothConditional(scenario));

			if (scenario.prefetch() != UNSPECIFIED || (fusion & Fuseable.SYNC) != 0) {
				verifier.accept(this.operatorErrorSourceVerifierFusedSync(scenario));
			}
			if (scenario.prefetch() != UNSPECIFIED || (fusion & Fuseable.ASYNC) != 0) {
				verifier.accept(this.operatorErrorSourceVerifierFusedAsync(scenario));
			}

		});
	}

	@Test
	public final void cancelOnSubscribe() {
		defaultEmpty = true;
		forEachScenario(scenarios_operatorSuccess(), s -> {

			Scenario<I, O> scenario = s.duplicate()
			                           .receiverEmpty()
			                           .receiverDemand(0);

			this.operatorNextVerifierBackpressured(scenario)
			    .consumeSubscriptionWith(Subscription::cancel)
			    .thenCancel()
			    .verify();

			this.operatorNextVerifier(scenario)
			    .consumeSubscriptionWith(Subscription::cancel)
			    .thenCancel()
			    .verify();

			this.operatorNextVerifierFused(scenario)
			    .consumeSubscriptionWith(Subscription::cancel)
			    .thenCancel()
			    .verify();

			this.operatorNextVerifierFusedSyncCancel(scenario);

			this.operatorNextVerifierFusedSyncConditionalCancel(scenario);

			this.operatorNextVerifierBothConditionalCancel(scenario)
			    .consumeSubscriptionWith(Subscription::cancel)
			    .thenCancel() //hit double cancel
			    .verify();

			this.operatorNextVerifierFusedBothConditional(scenario)
			    .consumeSubscriptionWith(Subscription::cancel)
			    .thenCancel()
			    .verify();
		});
	}

	@Test
	public final void sequenceOfNextAndCancel() {
		forEachScenario(scenarios_operatorSuccess(), scenario -> {

		});
	}

	@Test
	public final void sequenceOfNextAndError() {
		forEachScenario(scenarios_operatorSuccess(), scenario -> {
		});
	}

	@Test
	public final void sequenceOfNextAndComplete() {
		forEachScenario(scenarios_operatorSuccess(), scenario -> {
			Consumer<StepVerifier.Step<O>> verifier = scenario.verifier();

			if (verifier == null) {
				verifier = step -> scenario.applySteps(step)
				                           .verifyComplete();
			}

			int fusion = scenario.fusionMode();

			this.operatorNextVerifierBackpressured(scenario)
			    .consumeSubscriptionWith(s -> s.request(0))
			    .verifyComplete();

			verifier.accept(this.operatorNextVerifier(scenario));
			verifier.accept(this.operatorNextVerifierFusedTryNext(scenario));
			verifier.accept(this.operatorNextVerifierFused(scenario));

			if ((fusion & Fuseable.SYNC) != 0) {
				verifier.accept(this.operatorNextVerifierFusedSync(scenario));
				verifier.accept(this.operatorNextVerifierFusedConditionalSync(scenario));
			}

			if ((fusion & Fuseable.ASYNC) != 0) {
				verifier.accept(this.operatorNextVerifierFusedAsync(scenario));
				verifier.accept(this.operatorNextVerifierFusedConditionalAsync(scenario));
				this.operatorNextVerifierFusedAsyncState(scenario);
				this.operatorNextVerifierFusedConditionalAsyncState(scenario);
			}

			verifier.accept(this.operatorNextVerifierTryNext(scenario));
			verifier.accept(this.operatorNextVerifierBothConditional(scenario));
			verifier.accept(this.operatorNextVerifierConditionalTryNext(scenario));
			verifier.accept(this.operatorNextVerifierFusedBothConditional(scenario));
			verifier.accept(this.operatorNextVerifierFusedBothConditionalTryNext(scenario));

		});
	}

	final StepVerifier.Step<O> operatorNextVerifierBackpressured(Scenario<I, O> scenario) {
		int expected = scenario.receiverCount();
		int missing = expected - (expected / 2);

		long toRequest =
				expected == Integer.MAX_VALUE ? Long.MAX_VALUE : (expected - missing);

		StepVerifier.Step<O> step = StepVerifier.create(finiteSourceSync(scenario).hide()
		                                                                          .as(scenario.body())
		                                                                          .log(),
				toRequest);

		if (toRequest == Long.MAX_VALUE) {
			return scenario.applySteps(step);
		}
		return scenario.applySteps(expected - missing, step);
	}

	final StepVerifier.Step<O> operatorNextVerifierTryNext(Scenario<I, O> scenario) {
		TestPublisher<I> ts = TestPublisher.create();

		return StepVerifier.create(ts.flux()
		                             .as(scenario.body()))
		                   .then(() -> testPublisherSource(scenario, ts));
	}

	final StepVerifier.Step<O> operatorErrorSourceVerifierTryNext(Scenario<I, O> scenario) {
		TestPublisher<I> ts =
				TestPublisher.createNoncompliant(TestPublisher.Violation.CLEANUP_ON_TERMINATE);
		AtomicBoolean errorDropped = new AtomicBoolean();
		AtomicBoolean nextDropped = new AtomicBoolean();

		String dropped = droppedException().getMessage();
		Hooks.onErrorDropped(e -> {
			assertThat(e).hasMessage(dropped);
			errorDropped.set(true);
		});
		Hooks.onNextDropped(d -> {
			assertThat(d).isEqualTo(droppedItem());
			nextDropped.set(true);
		});
		return StepVerifier.create(ts.flux()
		                             .as(scenario.body()))
		                   .then(() -> {
			                   ts.error(exception());

			                   //verify drop path
			                   if (scenario.shouldHitDropErrorHookAfterTerminate()) {
				                   ts.complete();
				                   ts.error(droppedException());
				                   assertThat(errorDropped.get()).isTrue();
			                   }
			                   if (scenario.shouldHitDropNextHookAfterTerminate()) {
				                   ts.next(droppedItem());
				                   assertThat(nextDropped.get()).isTrue();
			                   }
		                   });
	}

	final StepVerifier.Step<O> operatorErrorSourceVerifier(Scenario<I, O> scenario) {
		TestPublisher<I> ts =
				TestPublisher.createNoncompliant(TestPublisher.Violation.CLEANUP_ON_TERMINATE,
						TestPublisher.Violation.REQUEST_OVERFLOW);
		AtomicBoolean nextDropped = new AtomicBoolean();

		Hooks.onNextDropped(d -> {
			assertThat(d).isEqualTo(droppedItem());
			nextDropped.set(true);
		});
		return StepVerifier.create(ts.flux()
		                             .hide()
		                             .as(scenario.body()))
		                   .then(() -> {
			                   ts.error(exception());

			                   //verify drop path
			                   if (scenario.shouldHitDropNextHookAfterTerminate()) {
				                   ts.next(droppedItem());
				                   assertThat(nextDropped.get()).isTrue();
			                   }
		                   });
	}

	final StepVerifier.Step<O> operatorNextVerifierFused(Scenario<I, O> scenario) {
		return StepVerifier.create(finiteSourceSync(scenario).as(scenario.body()));
	}

	@SuppressWarnings("unchecked")
	final void operatorNextVerifierFusedSyncCancel(Scenario<I, O> scenario) {
		if ((scenario.fusionMode() & Fuseable.SYNC) != 0) {
			StepVerifier.create(finiteSourceSync(scenario).as(scenario.body()), 0)
			            .consumeSubscriptionWith(s -> {
				            if (s instanceof Fuseable.QueueSubscription) {
					            Fuseable.QueueSubscription<O> qs =
							            ((Fuseable.QueueSubscription<O>) s);

					            assertThat(qs.requestFusion(Fuseable.SYNC | THREAD_BARRIER)).isEqualTo(
							            scenario.fusionModeThreadBarrier() & Fuseable.SYNC);

					            qs.size();
					            qs.isEmpty();
					            qs.clear();
					            assertThat(qs.isEmpty()).isTrue();
				            }
			            })
			            .thenCancel()
			            .verify();

			StepVerifier.create(finiteSourceSync(scenario).as(scenario.body()), 0)
			            .consumeSubscriptionWith(s -> {
				            if (s instanceof Fuseable.QueueSubscription) {
					            Fuseable.QueueSubscription<O> qs =
							            ((Fuseable.QueueSubscription<O>) s);
					            assertThat(qs.requestFusion(NONE)).isEqualTo(NONE);
				            }
			            })
			            .thenCancel()
			            .verify();
		}
	}

	@SuppressWarnings("unchecked")
	final void operatorNextVerifierFusedSyncConditionalCancel(Scenario<I, O> scenario) {
		if ((scenario.fusionMode() & Fuseable.SYNC) != 0) {
				StepVerifier.create(finiteSourceSync(scenario).as(scenario.body())
				                                              .filter(d -> true), 0)
				            .consumeSubscriptionWith(s -> {
					            if (s instanceof Fuseable.QueueSubscription) {
						            Fuseable.QueueSubscription<O> qs =
								            ((Fuseable.QueueSubscription<O>) ((Receiver) s).upstream());

						            assertThat(qs.requestFusion(Fuseable.SYNC | THREAD_BARRIER)).isEqualTo(
								            scenario.fusionModeThreadBarrier() & Fuseable.SYNC);

						            qs.size();
						            qs.isEmpty();
						            qs.clear();
						            assertThat(qs.isEmpty()).isTrue();
					            }
				            })
				            .thenCancel()
				            .verify();

				StepVerifier.create(finiteSourceSync(scenario).as(scenario.body())
				                                              .filter(d -> true), 0)
				            .consumeSubscriptionWith(s -> {
					            if (s instanceof Fuseable.QueueSubscription) {
						            Fuseable.QueueSubscription<O> qs =
								            ((Fuseable.QueueSubscription<O>) ((Receiver) s).upstream());
						            assertThat(qs.requestFusion(NONE)).isEqualTo(NONE);
					            }
				            })
				            .thenCancel()
				            .verify();
		}
	}

	@SuppressWarnings("unchecked")
	final StepVerifier.Step<O> operatorNextVerifierFusedSync(Scenario<I, O> scenario) {
		return StepVerifier.create(finiteSourceSync(scenario).as(scenario.body()))
		                   .expectFusion(Fuseable.SYNC);
	}

	@SuppressWarnings("unchecked")
	final StepVerifier.Step<O> operatorNextVerifierFusedConditionalSync(Scenario<I, O> scenario) {
		return StepVerifier.create(finiteSourceSync(scenario).as(scenario.body())
		                                                     .filter(d -> true))
		                   .expectFusion(Fuseable.SYNC);
	}

	final StepVerifier.Step<O> operatorNextVerifierFusedAsync(Scenario<I, O> scenario) {
		UnicastProcessor<I> up = UnicastProcessor.create();
		return StepVerifier.create(up.as(scenario.body()))
		                   .expectFusion(Fuseable.ASYNC)
		                   .then(() -> testUnicastSource(scenario, up));
	}

	final StepVerifier.Step<O> operatorNextVerifierFusedConditionalAsync(Scenario<I, O> scenario) {
		UnicastProcessor<I> up = UnicastProcessor.create();
		return StepVerifier.create(up.as(scenario.body())
		                             .filter(d -> true))
		                   .expectFusion(Fuseable.ASYNC)
		                   .then(() -> testUnicastSource(scenario, up));
	}

	@SuppressWarnings("unchecked")
	final void operatorNextVerifierFusedAsyncState(Scenario<I, O> scenario) {
		UnicastProcessor<I> up = UnicastProcessor.create();
		testUnicastSource(scenario, up);
		StepVerifier.create(up.as(scenario.body()), 0)
		            .consumeSubscriptionWith(s -> {
			            if (s instanceof Fuseable.QueueSubscription) {
				            Fuseable.QueueSubscription<O> qs =
						            ((Fuseable.QueueSubscription<O>) s);
				            qs.requestFusion(ASYNC);
				            if (up.downstream() != qs || scenario.prefetch() == UNSPECIFIED) {
					            qs.size(); //touch undeterministic
				            }
				            else {
					            assertThat(qs.size()).isEqualTo(up.size());
				            }
				            try {
					            qs.poll();
					            qs.poll();
					            qs.poll();
				            }
				            catch (Exception e) {
				            }
				            if (qs instanceof Trackable && ((Trackable) qs).getError() != null) {
					            assertThat(((Trackable) qs).getError()).hasMessage(
							            exception().getMessage());
					            if (up.downstream() != qs || scenario.prefetch() == UNSPECIFIED) {
						            qs.size(); //touch undeterministic
					            }
					            else {
						            assertThat(qs.size()).isEqualTo(up.size());
					            }
				            }
				            qs.clear();
				            assertThat(qs.size()).isEqualTo(0);
			            }
		            })
		            .thenCancel()
		            .verify();

		UnicastProcessor<I> up2 = UnicastProcessor.create();
		StepVerifier.create(up2.as(scenario.body()), 0)
		            .consumeSubscriptionWith(s -> {
			            if (s instanceof Fuseable.QueueSubscription) {
				            Fuseable.QueueSubscription<O> qs =
						            ((Fuseable.QueueSubscription<O>) s);
				            assertThat(qs.requestFusion(ASYNC | THREAD_BARRIER)).isEqualTo(
						            scenario.fusionModeThreadBarrier() & ASYNC);
			            }
		            })
		            .thenCancel()
		            .verify();
	}

	@SuppressWarnings("unchecked")
	final void operatorNextVerifierFusedConditionalAsyncState(Scenario<I, O> scenario) {
		UnicastProcessor<I> up = UnicastProcessor.create();
		testUnicastSource(scenario, up);
		StepVerifier.create(up.as(scenario.body())
		                      .filter(d -> true), 0)
		            .consumeSubscriptionWith(s -> {
			            if (s instanceof Fuseable.QueueSubscription) {
				            Fuseable.QueueSubscription<O> qs =
						            ((Fuseable.QueueSubscription<O>) ((Receiver) s).upstream());
				            qs.requestFusion(ASYNC);
				            if (up.downstream() != qs || scenario.prefetch() == UNSPECIFIED) {
					            qs.size(); //touch undeterministic
				            }
				            else {
					            assertThat(qs.size()).isEqualTo(up.size());
				            }
				            try {
					            qs.poll();
					            qs.poll();
					            qs.poll();
				            }
				            catch (Exception e) {
				            }
				            if (qs instanceof Trackable && ((Trackable) qs).getError() != null) {
					            assertThat(((Trackable) qs).getError()).hasMessage(
							            exception().getMessage());
					            if (up.downstream() != qs || scenario.prefetch() == UNSPECIFIED) {
						            qs.size(); //touch undeterministic
					            }
					            else {
						            assertThat(qs.size()).isEqualTo(up.size());
					            }
				            }
				            qs.clear();
				            assertThat(qs.size()).isEqualTo(0);
			            }
		            })
		            .thenCancel()
		            .verify();

		UnicastProcessor<I> up2 = UnicastProcessor.create();
		StepVerifier.create(up2.as(scenario.body())
		                       .filter(d -> true), 0)
		            .consumeSubscriptionWith(s -> {
			            if (s instanceof Fuseable.QueueSubscription) {
				            Fuseable.QueueSubscription<O> qs =
						            ((Fuseable.QueueSubscription<O>) ((Receiver) s).upstream());
				            assertThat(qs.requestFusion(ASYNC | THREAD_BARRIER)).isEqualTo(
						            scenario.fusionModeThreadBarrier() & ASYNC);
			            }
		            })
		            .thenCancel()
		            .verify();
	}

	@SuppressWarnings("unchecked")
	final StepVerifier.Step<O> operatorErrorSourceVerifierFused(Scenario<I, O> scenario) {
		UnicastProcessor<I> up = UnicastProcessor.create();
		AtomicBoolean errorDropped = new AtomicBoolean();
		AtomicBoolean nextDropped = new AtomicBoolean();
		String dropped = droppedException().getMessage();
		Hooks.onErrorDropped(e -> {
			assertThat(e).hasMessage(dropped);
			errorDropped.set(true);
		});
		Hooks.onNextDropped(d -> {
			assertThat(d).isEqualTo(droppedItem());
			nextDropped.set(true);
		});
		return StepVerifier.create(up.as(f -> new FluxFuseableExceptionOnPoll<>(f,
				exception()))
		                             .as(scenario.body()))
		                   .then(() -> {
			                   if (up.downstream() != null) {
				                   up.downstream()
				                     .onError(exception());

				                   //verify drop path

				                   if (scenario.shouldHitDropErrorHookAfterTerminate()) {
					                   up.downstream()
					                     .onComplete();
					                   up.downstream()
					                     .onError(droppedException());
					                   assertThat(errorDropped.get()).isTrue();
				                   }
				                   if (scenario.shouldHitDropNextHookAfterTerminate()) {

					                   FluxFuseableExceptionOnPoll.next(up.downstream(),
							                   droppedItem());
					                   assertThat(nextDropped.get()).isTrue();
					                   if (FluxFuseableExceptionOnPoll.shouldTryNext(up.downstream())) {
						                   nextDropped.set(false);
						                   FluxFuseableExceptionOnPoll.tryNext(up.downstream(),
								                   droppedItem());
						                   assertThat(nextDropped.get()).isTrue();
					                   }
				                   }

			                   }
		                   });
	}

	final StepVerifier.Step<O> operatorNextVerifierConditionalTryNext(Scenario<I, O> scenario) {
		return StepVerifier.create(finiteSourceSync(scenario).hide()
		                                                     .as(scenario.body())
		                                                     .filter(filter -> true),
				Math.max(scenario.producerCount(), scenario.receiverCount()))
		                   .consumeSubscriptionWith(s -> s.request(0));
	}

	final StepVerifier.Step<O> operatorNextVerifierBothConditional(Scenario<I, O> scenario) {
		TestPublisher<I> ts = TestPublisher.create();

		return StepVerifier.create(ts.flux()
		                             .as(scenario.body())
		                             .filter(filter -> true))
		                   .then(() -> testPublisherSource(scenario, ts));
	}

	final StepVerifier.Step<O> operatorNextVerifierBothConditionalCancel(Scenario<I, O> scenario) {
		TestPublisher<I> ts = TestPublisher.create();

		return StepVerifier.create(ts.flux()
		                             .as(scenario.body())
		                             .filter(filter -> true));
	}

	final StepVerifier.Step<O> operatorNextVerifierFusedBothConditional(Scenario<I, O> scenario) {
		return StepVerifier.create(finiteSourceSync(scenario).as(scenario.body())
		                                                     .filter(filter -> true));
	}

	final StepVerifier.Step<O> operatorErrorSourceVerifierConditionalTryNext(Scenario<I, O> scenario) {
		TestPublisher<I> ts =
				TestPublisher.createNoncompliant(TestPublisher.Violation.CLEANUP_ON_TERMINATE);
		AtomicBoolean errorDropped = new AtomicBoolean();
		AtomicBoolean nextDropped = new AtomicBoolean();

		String dropped = droppedException().getMessage();
		Hooks.onErrorDropped(e -> {
			assertThat(e).hasMessage(dropped);
			errorDropped.set(true);
		});
		Hooks.onNextDropped(d -> {
			assertThat(d).isEqualTo(droppedItem());
			nextDropped.set(true);
		});
		return StepVerifier.create(ts.flux()
		                             .as(scenario.body())
		                             .filter(filter -> true))
		                   .then(() -> {
			                   ts.error(exception());

			                   //verify drop path
			                   if (scenario.shouldHitDropErrorHookAfterTerminate()) {
				                   ts.complete();
				                   ts.error(droppedException());
				                   assertThat(errorDropped.get()).isTrue();
			                   }
			                   if (scenario.shouldHitDropNextHookAfterTerminate()) {
				                   ts.next(droppedItem());
				                   assertThat(nextDropped.get()).isTrue();
			                   }
		                   });
	}

	@Override
	protected final OperatorScenario<I, Flux<I>, O, Flux<O>> defaultScenarioOptions(
			OperatorScenario<I, Flux<I>, O, Flux<O>> defaultOptions) {
		Scenario<I, O> s = new Scenario<I, O>(null, null).applyAllOptions(defaultOptions)
		                                                 .producer(3,
				                                                 i -> (I) (i == 0 ?
						                                                 "test" :
						                                                 "test" + i))
		                                                 .receive(3,
				                                                 i -> (O) (i == 0 ?
						                                                 "test" :
						                                                 "test" + i));
		this.defaultScenario = s;
		return defaultScenarioOptions(s);
	}

	@SuppressWarnings("unchecked")
	protected Scenario<I, O> defaultScenarioOptions(Scenario<I, O> defaultOptions) {
		return defaultOptions;
	}

	final StepVerifier.Step<O> operatorErrorSourceVerifierConditional(Scenario<I, O> scenario) {
		TestPublisher<I> ts =
				TestPublisher.createNoncompliant(TestPublisher.Violation.CLEANUP_ON_TERMINATE);
		AtomicBoolean nextDropped = new AtomicBoolean();

		Hooks.onNextDropped(d -> {
			assertThat(d).isEqualTo(droppedItem());
			nextDropped.set(true);
		});
		return StepVerifier.create(ts.flux()
		                             .hide()
		                             .as(scenario.body())
		                             .filter(filter -> true))
		                   .then(() -> {
			                   ts.error(exception());

			                   //verify drop path
			                   if (scenario.shouldHitDropNextHookAfterTerminate()) {
				                   ts.next(droppedItem());
				                   assertThat(nextDropped.get()).isTrue();
			                   }
		                   });
	}

	final StepVerifier.Step<O> operatorNextVerifierFusedBothConditionalTryNext(Scenario<I, O> scenario) {
		return StepVerifier.create(finiteSourceSync(scenario).as(scenario.body())
		                                                     .filter(filter -> true),
				Math.max(scenario.producerCount(), scenario.receiverCount()))
		                   .consumeSubscriptionWith(s -> s.request(0));
	}

	final StepVerifier.Step<O> operatorErrorSourceVerifierFusedSync(Scenario<I, O> scenario) {
		return StepVerifier.create(Flux.just(item(0), item(1))
		                               .as(f -> new FluxFuseableExceptionOnPoll<>(f, exception()))
		                               .as(scenario.body()))
		                   .expectFusion(scenario.fusionMode() & SYNC);
	}

	final StepVerifier.Step<O> operatorErrorSourceVerifierFusedAsync(Scenario<I, O> scenario) {
		UnicastProcessor<I> up = UnicastProcessor.create();
		up.onNext(item(0));
		return StepVerifier.create(up.as(f -> new FluxFuseableExceptionOnPoll<>(f, exception()))
		                             .as(scenario.body()))
		                   .expectFusion(scenario.fusionMode() & ASYNC);
	}

	@SuppressWarnings("unchecked")
	final StepVerifier.Step<O> operatorErrorSourceVerifierFusedBothConditional(Scenario<I, O> scenario) {
		UnicastProcessor<I> up = UnicastProcessor.create();
		AtomicBoolean errorDropped = new AtomicBoolean();
		AtomicBoolean nextDropped = new AtomicBoolean();
		String dropped = droppedException().getMessage();
		Hooks.onErrorDropped(e -> {
			assertThat(e).hasMessage(dropped);
			errorDropped.set(true);
		});
		Hooks.onNextDropped(d -> {
			assertThat(d).isEqualTo(droppedItem());
			nextDropped.set(true);
		});
		return StepVerifier.create(up.as(f -> new FluxFuseableExceptionOnPoll<>(f,
				exception()))
		                             .as(scenario.body())
		                             .filter(filter -> true))
		                   .then(() -> {
			                   if (up.downstream() != null) {
				                   up.downstream()
				                     .onError(exception());

				                   //verify drop path
				                   if (scenario.shouldHitDropErrorHookAfterTerminate()) {
					                   up.downstream()
					                     .onComplete();
					                   up.downstream()
					                     .onError(droppedException());
					                   assertThat(errorDropped.get()).isTrue();
				                   }
				                   if (scenario.shouldHitDropNextHookAfterTerminate()) {
					                   FluxFuseableExceptionOnPoll.next(up.downstream(),
							                   droppedItem());
					                   assertThat(nextDropped.get()).isTrue();

					                   if (FluxFuseableExceptionOnPoll.shouldTryNext(up.downstream())) {
						                   nextDropped.set(false);
						                   FluxFuseableExceptionOnPoll.tryNext(up.downstream(),
								                   droppedItem());
						                   assertThat(nextDropped.get()).isTrue();
					                   }
				                   }

			                   }
		                   });
	}

	final StepVerifier.Step<O> operatorNextVerifier(Scenario<I, O> scenario) {
		return StepVerifier.create(finiteSourceSync(scenario).hide()
		                                                     .as(scenario.body()));
	}

	final StepVerifier.Step<O> operatorNextVerifierFusedTryNext(Scenario<I, O> scenario) {
		return StepVerifier.create(finiteSourceSync(scenario).as(scenario.body()),
				Math.max(scenario.producerCount(), scenario.receiverCount()))
		                   .consumeSubscriptionWith(s -> s.request(0));
	}

	@SuppressWarnings("unchecked")
	final void touchTreeState(Object parent){
		if (parent == null) {
			return;
		}

		if (parent instanceof Loopback) {
			assertThat(((Loopback) parent).connectedInput()).isNotNull();
			((Loopback) parent).connectedOutput();
		}

		if (parent instanceof Producer) {
			assertThat(((Producer) parent).downstream()).isNotNull();
		}

		if (parent instanceof MultiProducer){
			MultiProducer p = ((MultiProducer)parent);
			if(p.downstreamCount() != 0 || p.hasDownstreams()){
				Iterator<?> it = p.downstreams();
				while(it.hasNext()){
					touchInner(it.next());
				}
			}
		}

		if(parent instanceof MultiReceiver){
			MultiReceiver p = ((MultiReceiver)parent);
			if(p.upstreamCount() != 0){
				Iterator<?> it = p.upstreams();
				while(it.hasNext()){
					touchInner(it.next());
				}
			}
		}
	}

	final void touchInner(Object t){
		if(t instanceof Trackable){
			Trackable o = (Trackable)t;
			o.requestedFromDownstream();
			o.expectedFromUpstream();
			o.getPending();
			o.getCapacity();
			o.getError();
			o.limit();
			o.isTerminated();
			o.isStarted();
			o.isCancelled();
		}
		if(t instanceof Producer){
			((Producer)t).downstream();
		}
		if(t instanceof Loopback){
			((Loopback)t).connectedInput();
			((Loopback)t).connectedOutput();
		}
		if(t instanceof Receiver){
			((Receiver)t).upstream();
		}
	}

	protected int defaultLimit(Scenario<I, O> scenario) {
		if (scenario.prefetch() == UNSPECIFIED) {
			return QueueSupplier.SMALL_BUFFER_SIZE - (QueueSupplier.SMALL_BUFFER_SIZE >> 2);
		}
		if (scenario.prefetch() == Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return scenario.prefetch() - (scenario.prefetch() >> 2);
	}

	@Override
	protected List<Scenario<I, O>> scenarios_operatorSuccess() {
		return Collections.emptyList();
	}

	@Override
	protected List<Scenario<I, O>> scenarios_operatorError() {
		return Collections.emptyList();
	}

	@Override
	protected List<Scenario<I, O>> scenarios_errorFromUpstreamFailure() {
		return scenarios_operatorSuccess();
	}

	//assert
	protected List<Scenario<I, O>> scenarios_touchAndAssertState() {
		return scenarios_operatorSuccess();
	}

	//common source emitting
	protected void testPublisherSource(Scenario<I, O> scenario, TestPublisher<I> ts) {
		finiteSourceSync(scenario).subscribe(ts::next, ts::error, ts::complete);
	}

	//common fused source N prefilled
	protected void testUnicastSource(Scenario<I, O> scenario, UnicastProcessor<I> ts) {
		finiteSourceSync(scenario).subscribe(ts);
	}

	//common n unused item or dropped
	protected I item(int i) {
		return defaultScenario.producer()
		                      .apply(i);
	}

	//common first unused item or dropped
	@SuppressWarnings("unchecked")
	protected I droppedItem() {
		return (I) "dropped";
	}

	//unprocessable exception (dropped)
	protected RuntimeException droppedException() {
		return new RuntimeException("dropped");
	}

	//exception
	protected RuntimeException exception() {
		return new RuntimeException("test");
	}

	final Flux<I> finiteSourceSync(Scenario<I, O> scenario) {
		int p = scenario.producerCount();
		if (p == -1) {
			return infiniteSourceAsync(scenario);
		}
		if (p == 0) {
			return Flux.empty();
		}
		if (p == 1) {
			return Flux.just(scenario.producer()
			                         .apply(0));
		}
		return Flux.fromIterable(() -> new Iterator<I>() {
			int i = 0;

			@Override
			public boolean hasNext() {
				return i < p;
			}

			@Override
			public I next() {
				return scenario.producer()
				               .apply(i++);
			}
		});
	}

	final Flux<I> infiniteSourceAsync(Scenario<I, O> scenario) {
		ReplayProcessor<I> rp = ReplayProcessor.create();

		for (int i = 0; i < scenario.producerCount(); i++) {
			rp.onNext(scenario.producer()
			                  .apply(i));
		}

		return rp;
	}


}
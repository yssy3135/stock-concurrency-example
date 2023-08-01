## 스프링의 동시성 문제와 해결법

### 동시성 문제란?

동일한 자원에 여러 스레드가 동시에 접근하면서 발생하는 문제.

```java
@Entity
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long productId;

    private Long quantity;

    @Version
    private Long version;

    public Stock() {

    }

    public Stock(Long productId, Long quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    public Long getQuantity() {
        return quantity;
    }

	
    public void decrease(Long quantity) {
        if(this.quantity - quantity < 0 ) {
            throw new RuntimeException("foo");
        }

        this.quantity = this.quantity - quantity;
    }

}
```

위 메소드를 보자

Stock Entity의 decrease메소드를 주의해보자

1. A 스레드가 quanitty가 10일때 decrease 메소드를 호출했다
2. B 스레드가 같은 Stock Entity에 접근하여  A 스레드가 커밋하기 전에 같은 Stock을 조회했다.
3. B 스레드가 decrease메소드를 호출하고 커밋했다.
4. 그후 A 스레드가 커밋했다.

따져보면 서로 다른 스레드에서 같은 Entity를 조회해 decrease() 메서드는 두번 조회되었다.

하지만 quantity = 9로 1만 줄어들었다.

이렇게 데이터 정합성이 맞지 않는 상황이 발생한다.

이런 문제를 race condition 이라고 부른다.

> **Race Condition**
여러개의 스레드, 프로세드가 공유자원에 동시 접근할때 실행 순서에 따라 결과값이 달라질수 있는 현상
>

**동시성 이슈를 해결할 수 있는 방법을 알아보자**

### 1. Synchronized

- 1개의 스레드만 접근할 수 있도록 해주는 java의 기능
- 메소드 선언부에 synchronized 키워드를 붙여주면 한번에 한개의 스레드만 접근이 가능하다.

************문제점************

**Transactional 프록시 문제**

메소드 실행후 트랜잭션 종료시점에 commit하게 되는데
메소드가 끝나고 commit하는 시점에 다른 Thread가 해당 데이터를 조회하게 된다면 commit하기 이전 데이터에 접근하게 된다.

```java
@Around("target()")
  public Object transaction(ProceedingJoinPoint joinPoint) throws Throwable {
   
          log.info("[트랜잭션 시작]");
          Object result = joinPoint.proceed(); // 메서드 종료

					// 이 시점에 다른 스레드가 데이터에 접근
					
          log.info("[트랜잭션 커밋]");

          return result;
  }
```

********************************************************************************************************단일 서버 구성이라면 문제가 되지 않겠지만 서버가 여러대가 떠있을 경우에 보장이 불가능하다.********************************************************************************************************

하나의 프로세스 안에서만 보장된다.

하지만 서버가 여러대로 구성된다면 여러 스레드가 동시에 데이터가 접근이 가능하게 된다.

### 2. Pessimistic Lock ( 비관적 Lock )

실제 데이터에 Lock을 걸어서 정합성을 맞추는 방법

Spring Data Jpa 에서는 @Lock 어노테이션을 통해 손쉽게 설정할 수 있다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select s from Stock s where s.id = :id")
Stock findByIdWithPessimisticLock(Long id);
```

1. A스레드가 Lock을 걸고 데이터를 조회한다.
2. B스레드가 Lock을 걸고 데이터 획득을 시도한다.
3. 하지만 A스레드가 이미 Lock을 걸었기 때문에 B 스레드는 A스레드가 Lock을 반환할 때까지 대기한다.
4. A스레드가 반환하면 B 스레드가 Lock을 걸고 데이터를 조회한다.

**장점**

충돌이 빈번하게 일어난다면 Optimistic Lock 보다 성능이 좋다.

Lock을 통해 데이터를 제어하기 때문에 데이터 정합성이 보장된다.

**단점**

별도의 Lock을 설정하기때문에 성능 감소가 있다.

### 3. Optimisic Lock (낙관적 Lock)

실제로 Lock을 이용하지 않고 Version을 이용해 정합성을 맞추는 방법

1. A스레드가 version이 1인 객체를 조회
2. B스레드도 version이 1인 동일한 객체를 조회
3. A스레드가 먼저 수정하고 커밋
4. 그럼 이때 version이 1에서 2로 증가하게된다.
5. 이후 B 스레드가 커밋하려고 하면 버전이 일치하지 않음으로 실패하게 된다.
6. B스레드는 다시 읽은후 수정해 커밋을 해야 한다.

```java
@Version
@Query("select s from Stock s where s.id = :id")
Stock findByIdWithOptimisticLock(Long id);
```

**장점**

별도의 Lock을 잡지 않아 Pessimistic Lock 보다 성능상 이점이 있다.

**단점**

실패시 다시 재시도를 해야하기때문에 개발자가 재시도 로직을 작성해주어야 한다.

**충돌이 비번하게 일어난다면 Pessimistic Lock  빈번하게 일어나지 않는다면 Optimistic Lock**

### NamedLock

- 이름을 가진 MetaData Lock, 이름을 가진 Lock을 획득하고 해제 할때 까지 session을 이 Lock을 획득할 수 없게 된다.
- 트랜잭션이 종료될 때 Lock이 자동으로 해제되지 않기 때문에 별도의 명령어로 해제해주거나 선점 시간이 끝나야 Lock이 해제된다

- mysql getLock() 을 통해 NamedLock을 획득
- release()를 통해 Lock을 해제할 수 있다.

```java
@Query(value = "select get_lock(:key, 3000)", nativeQuery = true)
void getLock(String key);

@Query(value = "select release_lock(:key)", nativeQuery = true)
void releaseLock(String key);
```

실제로 사용할 때는 데이터 소스를 분리해서 사용해야한다. ( 별도의 jdbc사용..)

같은 데이터 소스를 사용할경우 connection_pool이 부족해져 다른 서비스에 영향을 끼칠 수 있다.

- Named Lock은 주로 분산 Lock을 구현할 때 사용한다.
- 데이터 삽입시에 정합성을 맞춰야하는 경우 사용할 수 있다.
- 트랜잭션이 종료될 때 Lock이 자동으로 해제되지 않기 때문에 주의해서 사용해야 하고
- 구현방법이 복잡할 수 있다.

## Redis를 활용한 Lock

### Lettuce활용

- Named Lock과 비슷한 방식
- 다른점은 redis 이용, 세션관리를 신경안써도 된다.
- 메소드 실행 전, 후로 Lock 획득, 해제를 해주어야 한다.

```java
public Boolean lock(Long key) {
        return redisTemplate
                .opsForValue()
                .setIfAbsent(generateKey(key), "lock", Duration.ofMillis(3_000));

}

public Boolean unlock(Long key) {
    return redisTemplate.delete(generateKey(key));
}

private String generateKey(Long key) {
    return key.toString();
}
```

```java
public void decrease(Long id, Long quantity) throws InterruptedException {
        while (!redisLockRepository.lock(id)){ //Lock 획득 시도
            Thread.sleep(100); //일정 term을 주어야 redis에 부하를 낮춰줄 수 있다.
        }
        try {
            stockService.decrease(id, quantity);
        } finally {
            redisLockRepository.unlock(id); //Lock 해제
        }

}
```

**장점**

구현이 간단하다

******단점******

Spin Lock 방식이므로 redis에 부하를 줄 수 있다.

그렇게 때문에 Lock 획득시에 일정 term을 주어야 redis에 부하를 낮춰줄 수 있다.

### Redisson

- sub/pub 방식의 Lock
- 점유하고있는 Lock을 해제 할때 sub(구독)하고 있는 다른 스레드들에서 메시지를 보내줌으로써

  Lock획득을 대기하고 있는 스레드들은 메시지를 받았을때 Lock 획득을 시도할 수 있다.

- spin Lock 방식인 Lettuce와 달리 메시지를 받았을때 한번, 혹은 몇번만 시도하면 되기 때문에 redis의 부하를 줄여줄 수 있다.
- Lock관련 클래스들을 라이브러리에서 제공해준다.
- 로직 실행 전,후로 Lock 획득, 해제는 해주어야 한다.

```java
public void decrease(Long id, Long quantity) {
        RLock lock = redissonClient.getLock(id.toString());

        try {
            boolean available = lock.tryLock(10, 1, TimeUnit.SECONDS);

            if(!available) {
                System.out.println("lock 획득 실패");
                return;
            }
            stockService.decrease(id, quantity);
        } catch (InterruptedException e) {
            throw new RuntimeException();
        } finally {
            lock.unlock();
        }

    }
```
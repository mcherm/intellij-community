// "Fix all 'Stream API call chain can be replaced with loop' problems in file" "true"

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Arrays.asList;

public class Main {
  public static long testNestedScope(List<String> list) {
    return list.stream().filter(l -> l != null).peek(lst -> (new Consumer<String>() {
      String lst = "hello";
      public void accept(String lst) {System.out.println(this.lst+ lst);}
    }).accept(lst)).co<caret>unt();
  }

  public static long testNestedScope2(List<String> list) {
    return list.stream().filter(l -> l != null).peek(lst -> (new Consumer<String>() {
      String list = "hello";
      public void accept(String list) {System.out.println(this.list+ lst);}
    }).accept(lst)).count();
  }

  private static long testAnonymousConflictingVar(Map<String, List<String>> strings) {
    return strings.entrySet().stream().filter(s -> !s.getKey().isEmpty()).mapToLong(e -> e.getValue().stream().filter(new Predicate<String>() {
      // we're inside anonymous class
      @Override
      public boolean test(String s) {
        return e.getKey().equals(s);
      }
    }).count()).sum();
  }

  private static long testLambdaConflictingVar(Map<String, List<String>> strings) {
    return strings.entrySet().stream().filter(s -> !s.getKey().isEmpty())
      .mapToLong(e -> count(e.getValue(), s -> e.getKey().equals(s)))
      .sum();
  }

  private static long testLambdaNotConflictingVar(Map<String, List<String>> strings) {
    return strings.entrySet().stream().filter(s -> !s.getKey().isEmpty())
      .mapToLong(e -> count(e.getValue(), sx -> e.getKey().equals(sx)))
      .sum();
  }

  private static long count(List<String> list, Predicate<String> p) {
    long count = 0;
    for(String s : list) {
      if(p.test(s)) count++;
    }
    return count;
  }

  public static void main(String[] args) {
    System.out.println(testNestedScope(asList("a", "b", "c")));
    System.out.println(testNestedScope2(asList("a", "b", "c")));

    Map<String, List<String>> map = new HashMap<>();
    map.put("", Arrays.asList("", "a", "b"));
    map.put("a", Arrays.asList("", "a", "b", "a"));
    map.put("b", Arrays.asList("", "a", "b"));
    map.put("c", Arrays.asList("", "a", "b"));
    System.out.println(testAnonymousConflictingVar(map));
    System.out.println(testLambdaConflictingVar(map));
    System.out.println(testLambdaNotConflictingVar(map));

  }
}
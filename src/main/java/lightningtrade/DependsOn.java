package lightningtrade;

import java.lang.annotation.Repeatable;

@Repeatable(DependsOnAlternatives.class)
public @interface DependsOn {
    String[] value();
}

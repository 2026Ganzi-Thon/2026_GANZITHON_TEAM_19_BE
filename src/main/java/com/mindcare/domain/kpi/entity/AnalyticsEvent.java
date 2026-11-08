package com.mindcare.domain.kpi.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="analytics_events") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class AnalyticsEvent {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(unique=true) private String eid; private String name;
 @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private Map<String,String> props;
 @Column(nullable=false) private Instant createdAt;
 public AnalyticsEvent(String eid,String name,Map<String,String> props){this.eid=eid;this.name=name;this.props=Map.copyOf(props);this.createdAt=Instant.now();}
}

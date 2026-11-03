package com.hassankarkaba.mprp.accounts;

import com.hassankarkaba.mprp.operations.Db;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SMTP acknowledgment loss may redeliver a local email; no financial operation is repeated. */
@Service
@ConditionalOnProperty(name = "mprp.worker", havingValue = "true")
public class MailDelivery {
  private final Db db;
  private final SecretCodec secrets;
  private final JavaMailSender sender;

  public MailDelivery(
      Db db,
      SecretCodec secrets,
      JavaMailSender sender,
      @Value("${spring.mail.host:mailpit}") String host) {
    this.db = db;
    this.secrets = secrets;
    this.sender = sender;
    if (!host.equals("mailpit") && !host.equals("localhost") && !host.equals("127.0.0.1"))
      throw new IllegalStateException("SMTP is limited to local Mailpit");
  }

  @Scheduled(fixedDelay = 2000)
  @Transactional
  public void deliver() {
    for (var row :
        db.query(
            "select * from account_mail where sent_at is null and due_at<=clock_timestamp() and"
                + " expires_at>clock_timestamp() order by created_at limit 20 for update skip"
                + " locked")) {
      UUID id = Db.uuid(row, "id");
      try {
        var message = new SimpleMailMessage();
        message.setFrom("sandbox@example.test");
        message.setTo(AccountRules.email(row.get("recipient").toString()));
        message.setSubject(row.get("subject").toString());
        message.setText(secrets.decrypt(row.get("encrypted_body").toString(), "mail:" + id));
        sender.send(message);
        db.exec(
            "update account_mail set sent_at=clock_timestamp(),encrypted_body='' where id=?", id);
      } catch (Exception e) {
        db.exec(
            "update account_mail set attempts=attempts+1,due_at=clock_timestamp()+interval '120"
                + " seconds' where id=?",
            id);
      }
    }
    db.exec(
        "update account_mail set encrypted_body='' where expires_at<=clock_timestamp() and"
            + " encrypted_body<>''");
    db.exec("delete from rate_event where occurred_at<clock_timestamp()-interval '15 minutes'");
  }
}

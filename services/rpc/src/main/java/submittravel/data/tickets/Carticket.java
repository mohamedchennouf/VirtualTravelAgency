package submittravel.data.tickets;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


public class Carticket extends ticket {
    private String start_date="";
    private String end_date="";
    private String model="";
    //private String name="";
    //private float prix=0;



    public Carticket(String name,String start_date,String end_date,String model,float prix){
        this.start_date=start_date;
        this.end_date=end_date;
        this.model=model;
        this.name=name;
        this.prix=prix;
    }

    public Carticket(){};


    @Override
    public String toString() {
        return "CarTicket{\n name:"+name+"  start date:" + start_date + "  end date:" + end_date+ "  model:"+model+"  prix:"+prix+"}";
    }

}

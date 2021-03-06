package esb.flows.technical;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import esb.flows.technical.data.*;
import esb.flows.technical.utils.*;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static esb.flows.technical.utils.Endpoints.*;

public class RetrieveFlight extends RouteBuilder {


    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2);

    public void configure() {

        /*onException(Exception.class).handled(true)
                .process(makeFakeFlight)
                .to(AGGREG_FLIGHT)
        .end()
        ;*/

        from(FILE_INPUT_FLIGHT)
                .onException(IOException.class).handled(true)
                .log("erreur capturée dans la lecture utilisateur : " + body().toString())
                .setHeader("err", constant("failinput"))
                .to(DEATH_POOL)
                .end()
                .routeId("csv-to-retrieve-req")
                .routeDescription("Recupérer un avion a partir de son id")
                .unmarshal(CsvFormat.buildCsvFormat())  // Body is now a List of Map<String -> Object>
                .split(body()) // on effectue un travaille en parralele sur la map >> on transforme tout ca en objet de type Flight
                    .parallelProcessing().executorService(WORKERS)
                        .process(csv2flightreq)
                .choice()
                    .when(header("err").isNotEqualTo("failinput"))
                        .log("Transformation du csv en FlightRequest : " + body().toString())
                        .to(FLIGHT_QUEUE)
                    .otherwise()
                        .log("erreur dans la requete utilisateur")
                        .process(makeFakeFlight)
                        .setHeader("type", constant("flight"))
                            .multicast()
                            .to(DEATH_POOL, AGGREG_TRAVELREQUEST)// tous les objetc flight sont ensuite mis dans la queue
                .endChoice()
        ;

        from(FLIGHT_QUEUE)
                .routeId("flight-queue")
                .routeDescription("queue des demandes de vols")
                .multicast() // on multicast sur les 2 transformateurs
                    .to(RETRIEVE_A_FLIGHTA, RETRIEVE_A_FLIGHTB)
        ;

        from(RETRIEVE_A_FLIGHTA) // transforme des FlightRequest
                .onException(IOException.class).handled(true)
                    .process(makeFakeFlight)
                    .log("erreur capturée transformation en requete fictive : " + body().toString() )
                    .to(AGGREG_FLIGHT)
                .end()
                .routeId("calling-flighta")
                .routeDescription("transfert de l'activemq vers le service document")
                .setHeader(Exchange.HTTP_METHOD, constant("POST")) // on choisis le type de requete (ici du POST en json)
                .setHeader("Content-Type", constant("application/json"))
                .setHeader("Accept", constant("application/json"))
                .process(flightreq2a) // on transforme tous les objets de type FlightRequest en JSON correspondant pour le service demandé
                .log("transformation de FlightRequest en requete Service A : " + body().toString())
                .inOut(FLIGHTSERVICE_ENDPOINTA) // on envoit la requete au service et on récupère la réponse
                .unmarshal().string()
                .process(answerservicea2flight)
                .log("transformation de la réponse en objet Flight : " + body().toString())
                .to(AGGREG_FLIGHT) // on stocke la reponse (ici dans un fichier)
        ;

        from(RETRIEVE_A_FLIGHTB) // meme princique que RETRIEVE_A_FLIGHTA
                .onException(IOException.class).handled(true)
                    .process(makeFakeFlight)
                    .log("erreur capturée transformation en requete fictive : " + body().toString() )
                    .to(AGGREG_FLIGHT)
                .end()
                .routeId("calling-flightb")
                .routeDescription("transfert de l'activemq vers le service document")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Type", constant("application/json"))
                .setHeader("Accept", constant("application/json"))
                .log("j'ai recu des trucs !" + body().toString())
                .process(flightreq2b) // on traite tous les objets flight reçus
                .log("transformation de FlightRequest en requete Service B : " + body().toString())
                .inOut(FLIGHTSERVICE_ENDPOINTB)
                .unmarshal().string()
                .process(answerserviceb2flight)
                .log("transformation de la réponse en objet Flight : " + body().toString())
                .to(AGGREG_FLIGHT)
        ;

        from(AGGREG_FLIGHT)
                .routeId("aggreg-flight")
                .routeDescription("l'aggregator des avions")
                .aggregate(constant(true), new FlightCarHotelAggregationStrategy())
                    .completionSize(2)
                .setHeader("type", constant("flight"))
                .log("Requete la moins chère retenue : " + body().toString())
                .to(AGGREG_TRAVELREQUEST)
        ;
    }

    private static Processor csv2flightreq = (Exchange exchange) -> { // fonction qui transforme la map issu du csv en objets de type FlightRequest
        try {
            Map<String, Object> data = (Map<String, Object>) exchange.getIn().getBody();
            FlightRequest p = new FlightRequest();
            p.setDate((String) data.get("date"));
            p.setEvent((String) data.get("event"));
            p.setDestination((String) data.get("destination"));
            p.setIsDirect((String) data.get("direct"));
            p.setOrigine((String) data.get("origine"));
            if(p.getDestination() == null || p.getOrigine() == null || p.getDate() == null || p.getEvent() == null || p.getIsDirect() == null){
                exchange.getIn().setHeader("err", "failinput");
            }
            else {
                exchange.getIn().setBody(p);
                exchange.getIn().setHeader("requete-id", (String) data.get("id"));
            }
        }
        catch(NullPointerException e){
            exchange.getIn().setHeader("err", "failinput");
        }
    };
//aaaa-mm-jj
    /*{
        "event": "One_Way_Price",
            "Outbound_date": "12-10-2017",
            "from" :"Nice",
            "to" : "Paris"
    }*/

    private static Processor flightreq2b = (Exchange exchange) -> { // fonction qui transforme un objet FlightRequest en json service b
        FlightRequest fr = (FlightRequest) exchange.getIn().getBody();
        String req = "{ \"event\": \"" + fr.getEvent() + "\", \"Outbound_date\": \""+fr.getDate()+"\",\"from\" :\"" +
                fr.getOrigine() + "\",\"to\" :\"" + fr.getDestination() + "\"}";
        exchange.getIn().setBody(req);
    };

    private static Processor flightreq2a = (Exchange exchange) -> { // fonction qui transforme un objet FlightRequest en json service a
        FlightRequest fr = (FlightRequest) exchange.getIn().getBody();
        String[] dateArray = fr.getDate().split("-");
        String parseDate = (dateArray[2] + "-" + dateArray[1] + "-" + dateArray[0]);
        //{ "event": "LIST", "filter": { "destination":"Paris", "date":"2017-09-30", "stops":["Marseille", "Toulouse"] } }
        String req = "{ \"event\": \"LIST\", \"filter\": {\"destination\" : \"" +
                    fr.getDestination() +"\", \"date\":\"" + parseDate+"\", \"isDirect\": " + fr.getIsDirect() +" } }";
        exchange.getIn().setBody(req);

    };
    /*{"Flights": {"Outbound": {
  "sorted_flights": [
    {
      "date": "12-10-2017",
      "prix": 450,
      "cmpny": "Ryanair",
      "nb_escales": 1,
      "destination": "Paris",
      "rating": 2.5,
      "duree": 4,
      "id": 3,
      "origine": "Nice"
    },
    {
      "date": "12-10-2017",
      "prix": 500,
      "cmpny": "Ryanair",
      "nb_escales": 1,
      "destination": "Paris",
      "rating": 3,
      "duree": 4,
      "id": 4,
      "origine": "Nice"
    },
    {
      "date": "12-10-2017",
      "prix": 2555,
      "cmpny": "AirFrance",
      "nb_escales": 1,
      "destination": "Paris",
      "rating": 5,
      "duree": 4,
      "id": 2,
      "origine": "Nice"
    }
  ],
  "DATE": "12-10-2017",
  "Number_of_Results": 3
}}}*/

    private static Processor makeFakeFlight = (Exchange exchange) -> {
        Flight f = new Flight();
        f.setPrice(String.valueOf(Integer.MAX_VALUE));
        f.setDate("err");
        f.setDestination("err");
        exchange.getIn().setBody(f);
    };

    private static Processor answerserviceb2flight = (Exchange exchange) -> { // transforme la liste de flight en un flight unique (le moins cher)

        Flight resultat = new Flight();
        resultat.setPrice(String.valueOf(Integer.MAX_VALUE));
        resultat.setDate("not found");
        resultat.setDestination("not found");
        try {
            JsonParser jparser = new JsonParser();
            JsonElement obj = jparser.parse((String) exchange.getIn().getBody());
            JsonObject json = obj.getAsJsonObject();
            JsonElement l1 = json.get("Flights");
            JsonObject l1bis = l1.getAsJsonObject();
            JsonElement l2 = l1bis.get("Outbound");
            JsonObject l2bis = l2.getAsJsonObject();
            JsonElement l3 = l2bis.get("sorted_flights");
            JsonArray list = l3.getAsJsonArray();
            for (JsonElement j : list) {
                JsonObject jsontmp = j.getAsJsonObject();
                if(Integer.valueOf(jsontmp.get("prix").getAsString()) < Integer.valueOf(resultat.getPrice())){
                    resultat.setPrice(jsontmp.get("prix").getAsString());
                    resultat.setDestination(jsontmp.get("destination").getAsString());
                    resultat.setDate(jsontmp.get("date").getAsString());
                }
            }
        }
        catch(Exception e){
            e.printStackTrace();
            exchange.getIn().setBody(resultat);
        }

        exchange.getIn().setBody(resultat);

    };
     /*{
  "size": 3,
  "vols": [
    {
      "date": "2017-10-12",
      "price": "300",
      "destination": "Paris",
      "id": "3",
      "stops": [
        "Marseille",
        "Toulouse"
      ],
      "isDirect": false
    },
    {
      "date": "2017-10-12",
      "price": "350",
      "destination": "Paris",
      "id": "4",
      "stops": [
        "Marseille",
        "Toulouse"
      ],
      "isDirect": false
    },
    {
      "date": "2017-10-12",
      "price": "350",
      "destination": "Paris",
      "id": "4",
      "stops": [
        "Marseille",
        "Toulouse"
      ],
      "isDirect": false
    }
  ]
}*/


    private static Processor answerservicea2flight = (Exchange exchange) -> {
        Flight resultat = new Flight();
        resultat.setPrice(String.valueOf(Integer.MAX_VALUE));
        resultat.setDate("not found");
        resultat.setDestination("not found");
        try {
            JsonParser jparser = new JsonParser();
            JsonElement obj = jparser.parse((String) exchange.getIn().getBody());
            JsonObject json = obj.getAsJsonObject();
            JsonElement l1 = json.get("vols");
            JsonArray list = l1.getAsJsonArray();
            System.out.println("j'ai transformé le json") ;
            for(JsonElement j : list){
                JsonObject jsontmp = j.getAsJsonObject();
                if(Integer.valueOf(jsontmp.get("price").getAsString()) < Integer.valueOf(resultat.getPrice())){
                    resultat.setDestination(jsontmp.get("destination").getAsString());
                    resultat.setDate(jsontmp.get("date").getAsString());
                    resultat.setPrice(jsontmp.get("price").getAsString());
                }

            }
        }
        catch(Exception e){
            exchange.getIn().setBody(resultat);
        }
        exchange.getIn().setBody(resultat);
    };

}

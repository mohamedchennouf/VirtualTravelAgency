package esb.flows.technical;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import esb.flows.technical.data.*;
import esb.flows.technical.utils.CsvFormat;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static esb.flows.technical.utils.Endpoints.*;

public class RetrieveFlight extends RouteBuilder {


    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2);
    @Override
    public void configure() throws Exception {
        from(FILE_INPUT_DIRECTORY)
                .routeId("csv-to-retrieve-req")
                .routeDescription("Recupérer un avion a partir de son id")
                .unmarshal(CsvFormat.buildCsvFormat())  // Body is now a List of Map<String -> Object>
                .split(body()) // on effectue un travaille en parralele sur la map >> on transforme tout ca en objet de type Flight
                    .parallelProcessing().executorService(WORKERS)
                        .process(csv2flightreq)
                .log("je suis passé par la")
                .to(FLIGHT_QUEUE) // tous les objetc flight sont ensuite mis dans la queue
        ;

        from(FLIGHT_QUEUE)
                .routeId("flight-queue")
                .routeDescription("queue des demandes de vols")
                .multicast() // on multicast sur les 2 transformateurs
                    .to(RETRIEVE_A_FLIGHTA, RETRIEVE_A_FLIGHTB)
        ;

        from(RETRIEVE_A_FLIGHTA) // transforme des FlightRequest
                .routeId("calling-flighta")
                .routeDescription("transfert de l'activemq vers le service document")
                .setHeader(Exchange.HTTP_METHOD, constant("POST")) // on choisis le type de requete (ici du POST en json)
                .setHeader("Content-Type", constant("application/json"))
                .setHeader("Accept", constant("application/json"))
                .log("j'ai recu des trucs !")
                .process(flightreq2a) // on transforme tous les objets de type FlightRequest en JSON correspondant pour le service demandé
                .inOut(FLIGHTSERVICE_ENDPOINTA) // on envoit la requete au service et on récupère la réponse
                .unmarshal().string()
                .process(answerservicea2flight)
                .marshal().json(JsonLibrary.Jackson)
                .to(CAMEL_OUTPUT_TESTA) // on stocke la reponse (ici dans un fichier)
        ;

        from(RETRIEVE_A_FLIGHTB) // meme princique que RETRIEVE_A_FLIGHTA
                .routeId("calling-flightb")
                .routeDescription("transfert de l'activemq vers le service document")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Type", constant("application/json"))
                .setHeader("Accept", constant("application/json"))
                .log("j'ai recu des trucs !" + body().toString())
                .process(flightreq2b) // on traite tous les objets flight reçus
                .inOut(FLIGHTSERVICE_ENDPOINTB)
                .unmarshal().string()
                .process(answerserviceb2flight)
                .marshal().json(JsonLibrary.Jackson)
                .to(CAMEL_OUTPUT_TESTB)
        ;
    }

    private static Processor csv2flightreq = (Exchange exchange) -> { // fonction qui transforme la map issu du csv en objets de type FlightRequest
        Map<String, Object> data = (Map<String, Object>) exchange.getIn().getBody();
        FlightRequest p =  new FlightRequest();
        p.setDate((String) data.get("date"));
        p.setEvent((String) data.get("event"));
        p.setDestination((String) data.get("destination"));
        p.setIsDirect((String) data.get("direct"));
        p.setOrigine((String) data.get("origine"));
        exchange.getIn().setBody(p);
    };

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
        //{ "event": "LIST", "filter": { "destination":"Paris", "date":"2017-09-30", "stops":["Marseille", "Toulouse"] } }
        String req = "{ \"event\": \"LIST\", \"filter\": {\"destination\" : \"" +
                    fr.getDestination() +"\", \"date\":\"" + fr.getDate()+"\", \"isDirect\": " + fr.getIsDirect() +" } }";
        exchange.getIn().setBody(req);

    };
    /*{
        "size": 1,
            "vols": [{
        "date": "2017-09-30",
                "price": "200",
                "destination": "Paris",
                "id": "1",
                "stops": [
        "Marseille",
                "Toulouse"
    ],
        "isDirect": false
    }]
    }*/

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

    private static Processor answerserviceb2flight = (Exchange exchange) -> { // transforme la liste de flight en un flight unique (le moins cher)

        Flight resultat = new Flight();
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
            ArrayList<Flight> listFlight = new ArrayList<>();
            for (JsonElement j : list) {
                Flight flighttmp = new Flight();
                JsonObject jsontmp = j.getAsJsonObject();
                flighttmp.setPrice(jsontmp.get("prix").getAsString());
                flighttmp.setDestination(jsontmp.get("destination").getAsString());
                flighttmp.setDate(jsontmp.get("date").getAsString());
                listFlight.add(flighttmp);
            }
            resultat.setPrice(String.valueOf(Integer.MAX_VALUE));
            for (Flight f : listFlight) {
                if (Integer.valueOf(f.getPrice()) < Integer.valueOf(resultat.getPrice())) {
                    resultat = f;
                }
            }
        }
        catch(Exception e){
            resultat.setPrice(String.valueOf(Integer.MAX_VALUE));
            resultat.setDate("not found");
            resultat.setDestination("not found");
        }

        exchange.getIn().setBody(resultat);

    };


    private static Processor answerservicea2flight = (Exchange exchange) -> {
        String tmpStr = (String) exchange.getIn().getBody();
        String[] array = tmpStr.split("\"");
        Flight fl = new Flight();
        try {
            fl.setDate(array[7]);
            fl.setDestination(array[15]);
            fl.setPrice(array[11]);
        }
        catch(IndexOutOfBoundsException e){
            fl.setDate("not found");
            fl.setDestination(("not found"));
            fl.setPrice(String.valueOf(Integer.MAX_VALUE));
        }
        exchange.getIn().setBody(fl);
    };

}
package flyreservation;

import org.json.JSONArray;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Collection;


@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class FlyReservationService {


    @GET
    public Response getAllReservations(){
        Collection<FlyReservation> resas = Storage.findAll();
        JSONArray result = new JSONArray();
        for(FlyReservation f : resas){
            result.put(f.toString());
        }
        return Response.ok().entity(result.toString(2)).build();
    }

    @Path("/{date}/{dest}")
    @GET
    public Response getWithDateandDest(@PathParam("date") String date, @PathParam("dest") String dest, @QueryParam("direct") boolean isDirect, @QueryParam("maxEscale") int maxEscale){
        if(Storage.read(dest,date) == null){
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        JSONArray result = new JSONArray();
        for(FlyReservation f : Storage.read(dest,date)){
            if(isDirect){
                if(f.isDirect()){
                    result.put(f.toString());
                }
            }
            else if(Integer.valueOf(maxEscale) != null && f.getStops().size() <= Integer.valueOf(maxEscale)){
                result.put(f.toString());
            }
            else{
                result.put(f.toString());
            }
        }
        return Response.ok().entity(result.toString(2)).build();

    }
}